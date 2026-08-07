import { entersMedicationHorizon } from "@taskrx/shared";
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
 * Arms the deferred reminder for a snoozed Occurrence, and reports whether it
 * armed anything.
 *
 * The notification fires at `snoozeUntil` and deep-links back to the answer
 * screen with the original `medicationId` and `scheduledTime`. The original
 * scheduled time is preserved so the Reminder Instance answered from the snooze
 * notification still records the moment it was deferred from.
 *
 * `snoozeUntil` is an instant rather than a duration because the caller has
 * already written it to the Instance: a duration would be resolved against a
 * second reading of the clock, and the row and the alarm would name different
 * moments.
 *
 * The title is read from `reminders`, which is the table `scheduler.ts` armed
 * the alarm from. Reading the legacy `medications` table here meant the snooze
 * could disagree with the notification it was snoozing — a `weekly` reminder has
 * no legacy row to name at all. Both tables are gone now; `reminders` is simply
 * where a reminder lives.
 *
 * ## Why a task arms nothing, and says so
 *
 * Everything below is medication-Tier: the medication channel, HIGH importance,
 * and `SET_ALARM_CLOCK`. ADR 0003 keeps that combination for doses, so a task
 * cannot borrow it by the side door of asking to be reminded again — which is
 * exactly how the app's *last* `SET_EXACT_AND_ALLOW_WHILE_IDLE` survived a
 * migration that had moved every other path off it.
 *
 * So for a task this records the deferral and arms nothing, and the boolean
 * says which happened rather than leaving the caller to guess: the person is
 * told their task was deferred, not that they will be reminded. Task-Tier
 * delivery is #14, and this is the second place that will need a trigger.
 *
 * Renamed from `snoozeDose`: "dose" is the quantity of a drug (CONTEXT.md), and
 * what is being deferred here is an Occurrence of either Kind.
 */
export async function snoozeReminder(
  reminderId: string,
  scheduledTime: number,
  snoozeUntil: number
): Promise<boolean> {
  const reminder = await remindersRepo.get(reminderId);

  // A reminder this device cannot find arms nothing either. ADR 0003 makes the
  // exclusion hold "by construction rather than by policy", and a row that is
  // absent is a row not known to be a medication — a not-yet-pulled or
  // just-deleted task reached from a stale deep link would otherwise take the
  // alarm-clock primitive on the medication channel. The fallback this replaces
  // armed one under the title "Medication", and is unreachable in practice
  // anyway: `/dose/log` refuses to render at all when this same lookup returns
  // null.
  if (reminder === null || !entersMedicationHorizon(reminder)) {
    return false;
  }

  const dosageLabel =
    reminder.dosage !== undefined ? ` (${reminder.dosage})` : "";

  // Both derived from the one instant the caller decided, never from a second
  // read of the clock. The Instance the caller stored and the alarm armed here
  // are the same deferral, and two `Date.now()` calls would let them name
  // moments milliseconds apart — which is also enough to make the notification
  // id disagree with the one a later cancel would compute.
  const snoozeMinutes = Math.max(
    1,
    Math.round((snoozeUntil - Date.now()) / 60_000)
  );
  const id = buildSnoozeNotificationId(reminderId, scheduledTime, snoozeUntil);

  const notification: Notification = {
    id,
    title: `${reminder.name}${dosageLabel} (snoozed)`,
    body: `Reminder snoozed for ${String(snoozeMinutes)} minutes`,
    data: {
      medicationId: reminderId,
      scheduledTime: String(scheduledTime),
      snoozed: "true",
    },
    android: {
      channelId: MEDICATION_REMINDER_CHANNEL_ID,
      smallIcon: "ic_launcher",
      pressAction: {
        // Not renamed with ADR 0001 — see `scheduler.ts` for why this string
        // is frozen.
        id: "mark-taken",
        launchActivity: "default",
      },
      importance: AndroidImportance.HIGH,
      autoCancel: true,
    },
  };

  const trigger: TimestampTrigger = {
    type: TriggerType.TIMESTAMP,
    timestamp: snoozeUntil,
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
  return true;
}

/**
 * Build a deterministic notification id for a snooze so multiple snoozes
 * for the same reminder don't collide. Includes the original scheduled time
 * and the snooze fire time.
 */
function buildSnoozeNotificationId(
  reminderId: string,
  scheduledTime: number,
  snoozeUntil: number
): string {
  return `${SNOOZE_NOTIFICATION_ID_PREFIX}${buildBaseNotificationId(reminderId, scheduledTime)}-${String(snoozeUntil)}`;
}
