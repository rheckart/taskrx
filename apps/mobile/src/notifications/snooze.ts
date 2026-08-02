import notifee, {
  AlarmType,
  AndroidImportance,
  type Notification,
  TriggerType,
  type TimestampTrigger,
} from "react-native-notify-kit";

import * as remindersRepo from "../db/repos/reminders";

import {
  buildNotificationId as buildBaseNotificationId,
  MEDICATION_REMINDER_CHANNEL_ID,
  SNOOZE_NOTIFICATION_ID_PREFIX,
} from "./scheduler";

/**
 * Schedules a one-off snooze notification for a dose.
 *
 * The notification fires `snoozeMinutes` from now and deep-links back to
 * the dose logging screen with the original `medicationId` and
 * `scheduledTime`. The original scheduled time is preserved so the dose
 * log row created from the snooze notification still records the intended
 * dose time.
 *
 * The title is read from `reminders`, which is the table `scheduler.ts` armed
 * the alarm from. Reading the legacy `medications` table here meant the snooze
 * could disagree
 * with the notification it was snoozing — a `weekly` reminder has no
 * legacy row to name at all. Both tables are gone now; `reminders` is simply
 * where a reminder lives.
 */
export async function snoozeDose(
  medicationId: string,
  scheduledTime: number,
  snoozeMinutes = 15
): Promise<void> {
  const med = await remindersRepo.get(medicationId);
  const medName = med?.name ?? "Medication";
  const dosageLabel = med?.dosage !== undefined ? ` (${med.dosage})` : "";

  const snoozeTime = Date.now() + snoozeMinutes * 60_000;
  const id = buildSnoozeNotificationId(medicationId, scheduledTime, snoozeTime);

  const notification: Notification = {
    id,
    title: `${medName}${dosageLabel} (snoozed)`,
    body: `Reminder snoozed for ${String(snoozeMinutes)} minutes`,
    data: {
      medicationId,
      scheduledTime: String(scheduledTime),
      snoozed: "true",
    },
    android: {
      channelId: MEDICATION_REMINDER_CHANNEL_ID,
      smallIcon: "ic_launcher",
      pressAction: {
        id: "mark-taken",
        launchActivity: "default",
      },
      importance: AndroidImportance.HIGH,
      autoCancel: true,
    },
  };

  const trigger: TimestampTrigger = {
    type: TriggerType.TIMESTAMP,
    timestamp: snoozeTime,
    alarmManager: {
      // The same primitive `scheduler.ts` arms, and for the same reason it
      // documents at length there. This was the last `SET_EXACT_AND_ALLOW_WHILE_IDLE`
      // in the app: todo 7 moved the scheduler off it and never reached this
      // file, leaving the invariant `README.md` states unqualified — "do not
      // revert the notification AlarmType" — true everywhere except the one
      // path a user reaches by asking to be reminded again. Doze is the lesser
      // risk for an alarm 15 minutes out; OEM aggressive-kill is the real one.
      type: AlarmType.SET_ALARM_CLOCK,
    },
  };

  await notifee.createTriggerNotification(notification, trigger);
}

/**
 * Build a deterministic notification id for a snooze so multiple snoozes
 * for the same dose don't collide. Includes the original scheduled time
 * and the snooze fire time.
 */
function buildSnoozeNotificationId(
  medicationId: string,
  scheduledTime: number,
  snoozeTime: number
): string {
  return `${SNOOZE_NOTIFICATION_ID_PREFIX}${buildBaseNotificationId(medicationId, scheduledTime)}-${String(snoozeTime)}`;
}
