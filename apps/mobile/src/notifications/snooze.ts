import notifee, {
  AlarmType,
  AndroidImportance,
  type Notification,
  TriggerType,
  type TimestampTrigger,
} from "@notifee/react-native";

import * as medicationsRepo from "../db/repos/medications.js";

import {
  buildNotificationId as buildBaseNotificationId,
  MEDICATION_REMINDER_CHANNEL_ID,
} from "./scheduler.js";

/**
 * Schedules a one-off snooze notification for a dose.
 *
 * The notification fires `snoozeMinutes` from now and deep-links back to
 * the dose logging screen with the original `medicationId` and
 * `scheduledTime`. The original scheduled time is preserved so the dose
 * log row created from the snooze notification still records the intended
 * dose time.
 */
export async function snoozeDose(
  medicationId: string,
  scheduledTime: number,
  snoozeMinutes = 15
): Promise<void> {
  const med = await medicationsRepo.get(medicationId);
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
      type: AlarmType.SET_EXACT_AND_ALLOW_WHILE_IDLE,
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
  return `snooze-${buildBaseNotificationId(medicationId, scheduledTime)}-${String(snoozeTime)}`;
}
