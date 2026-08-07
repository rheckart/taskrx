import { tierFor } from "@taskrx/shared";
import notifee, {
  type Notification,
  TriggerType,
  type TimestampTrigger,
} from "react-native-notify-kit";

import * as remindersRepo from "../db/repos/reminders";

import {
  androidFor,
  buildNotificationId as buildBaseNotificationId,
  SNOOZE_NOTIFICATION_ID_PREFIX,
  TIER_DELIVERY,
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
 * ## The Tier follows the Kind here too
 *
 * A snooze is the one path a person reaches by *asking* to be reminded again,
 * which makes it the likeliest side door for a task to borrow the alarm-clock
 * primitive — and that is not a hypothetical: it is exactly how the app's last
 * `SET_EXACT_AND_ALLOW_WHILE_IDLE` survived a migration that had moved every
 * other path off it. So the Tier is decided from the Kind here as it is in
 * `scheduler.ts`: a dose is deferred with `SET_ALARM_CLOCK` on the medication
 * channel, a task with the ordinary trigger on the task channel (ADR 0007),
 * and no argument passed in can swap them.
 *
 * A task's deferral armed nothing at all until #14, because the task Tier had
 * no trigger to arm. It has one now, so the boolean below is `false` only for a
 * reminder this device cannot find.
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

  // A reminder this device cannot find arms nothing. ADR 0003 makes the Tier
  // split hold "by construction rather than by policy", and a row that is
  // absent is a row of no known Kind — a not-yet-pulled or just-deleted task
  // reached from a stale deep link would otherwise have to be guessed at, and
  // the fallback this replaces guessed "Medication" and took the alarm-clock
  // primitive. It is unreachable in practice anyway: `/dose/log` refuses to
  // render at all when this same lookup returns null.
  if (reminder === null) {
    return false;
  }

  // One question, asked once. Everything the two Tiers are told differently is
  // `TIER_DELIVERY`'s job, so this file no longer decides a channel, an
  // importance, a press action or an alarm primitive — it only says which Tier
  // the deferral is on, and the table beside the scheduler answers the rest in
  // the same words the scheduler gets.
  const tier = tierFor(reminder.kind);
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
      // `reminderId` is the post-cutover key and `medicationId` the one alarms
      // armed before it carry; `tap-handler.ts` prefers the first and accepts
      // the second. A task snooze sends only the new name, because no task
      // alarm predates the cutover and the old one would be a lie about which
      // table the id belongs to. This is the one thing here that is genuinely
      // about the Kind rather than about the Tier, which is why it is the one
      // thing the table below does not answer.
      ...(tier === "medication" ? { medicationId: reminderId } : {}),
      reminderId,
      scheduledTime: String(scheduledTime),
      snoozed: "true",
    },
    android: androidFor(tier),
  };

  const trigger: TimestampTrigger = {
    type: TriggerType.TIMESTAMP,
    timestamp: snoozeUntil,
    // Read from the same table the scheduler arms from, rather than chosen
    // again here. This path is where the app's last
    // `SET_EXACT_AND_ALLOW_WHILE_IDLE` survived a migration that moved every
    // other one off it, and it survived precisely because it named its own
    // primitive.
    alarmManager: TIER_DELIVERY[tier].alarmManager,
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
