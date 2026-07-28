import type { Reminder, ScheduledOccurrence } from "@taskrx/shared";
import { nextOccurrences, selectUpcomingOccurrences } from "@taskrx/shared";
import notifee, {
  AlarmType,
  AndroidImportance,
  type Notification,
  type TimestampTrigger,
  TriggerType,
} from "react-native-notify-kit";

import * as remindersRepo from "../db/repos/reminders";

/**
 * The Android channel id is persisted on the device the first time the channel
 * is created, and the user's per-channel sound / importance / DND overrides are
 * keyed to it. Renaming the constant is safe; changing this *string* is not —
 * it would orphan the existing channel and silently reset every override the
 * user had set. The value therefore stays `medication_reminders` even though
 * the scheduler now also fires for `task` reminders.
 */
export const MEDICATION_REMINDER_CHANNEL_ID = "medication_reminders";
export const MEDICATION_REMINDER_CHANNEL_NAME = "Medication Reminders";

/**
 * Per-reminder default used by the debug screen when scheduling a single
 * reminder in isolation. Production paths go through `rescheduleAll`,
 * which uses `MAX_GLOBAL_TRIGGERS` instead.
 */
const DEFAULT_UPCOMING_COUNT = 10;

/**
 * iOS enforces a hard cap of 64 pending local notifications per app.
 * Android has no cap but 64 is a natural rolling horizon and keeps parity
 * with the future iOS build. Selection is global across all active reminders
 * (see `selectUpcomingOccurrences` in `@taskrx/shared`), not per-reminder.
 */
const MAX_GLOBAL_TRIGGERS = 64;

/**
 * Creates the Android notification channel for medication reminders.
 *
 * HIGH importance lets the notification bypass Do Not Disturb and alert the
 * user audibly / visually. This must be called before scheduling any
 * notifications.
 */
export async function createNotificationChannel(): Promise<void> {
  await notifee.createChannel({
    id: MEDICATION_REMINDER_CHANNEL_ID,
    name: MEDICATION_REMINDER_CHANNEL_NAME,
    importance: AndroidImportance.HIGH,
    vibration: true,
    vibrationPattern: [300, 500],
  });
}

/**
 * The notification id for a scheduled occurrence.
 *
 * Deliberately still `${id}-${fireAt}` rather than the engine's
 * `occurrenceId`. Migration 005 backfills `reminders` preserving the primary
 * key, so a migrated reminder's id equals the medication id that generated the
 * alarms already pending on the device. Keeping the format byte-identical
 * means the first post-upgrade `rescheduleAll` diffs those existing alarms as
 * *desired* and leaves them armed, instead of cancelling and recreating all
 * 64 — which would open a churn window on exactly the boot where the user is
 * least able to tolerate a missed dose.
 */
export function buildNotificationId(
  reminderId: string,
  scheduledTime: number
): string {
  return `${reminderId}-${String(scheduledTime)}`;
}

function buildNotification(occurrence: ScheduledOccurrence): Notification {
  const dosageLabel =
    occurrence.dosage !== undefined ? ` (${occurrence.dosage})` : "";
  return {
    id: buildNotificationId(occurrence.reminderId, occurrence.fireAt),
    title: occurrence.name,
    body: `Time to take${dosageLabel}`,
    data: {
      reminderId: occurrence.reminderId,
      // Compatibility key. Alarms scheduled before the unified cutover carry
      // only `medicationId`, and `tap-handler` / `snooze` / the `/dose/log`
      // route still speak that name. Both keys hold the same value because
      // migration 005 preserves the primary key. Drop this once the read
      // screens move off `medications`.
      medicationId: occurrence.reminderId,
      scheduledTime: String(occurrence.fireAt),
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
}

/**
 * Builds the AlarmManager trigger for a scheduled occurrence.
 *
 * Uses `SET_ALARM_CLOCK` — the strongest Android guarantee available for a
 * scheduled notification, and the mechanism empirically verified firing on
 * a physical Android 17 device via the native MAUI build (see
 * `archive/dotnet-maui:src/TaskRx.Maui/Platforms/Android/Notifications/AndroidNotificationScheduler.cs:55-63`).
 *
 * Why not the weaker exact-alarm-with-doze-bypass primitive (the pre-todo-7
 * default):
 *   * `SET_ALARM_CLOCK` is exact + wakes + Doze-bypassing PLUS OEM-resilient
 *     (vendor aggressive-kill policies — Xiaomi MIUI, Oppo ColorOS, Huawei
 *     EMUI, Vivo FuntouchOS — generally respect setAlarmClock because it
 *     is the same mechanism the stock Clock app uses).
 *   * The weaker primitive has NO OEM resilience — it is the reason
 *     upstream Notifee #961 documented "SET_EXACT doesn't work in Doze".
 *
 * Deliberate tradeoff: `SET_ALARM_CLOCK` renders a persistent status-bar
 * alarm-clock glyph while any trigger is pending. With a 64-occurrence rolling
 * horizon there is always at least one pending, so the icon is effectively
 * permanent. This is acceptable and arguably a feature for a medication
 * reminder — it signals to the user that reminders are armed, which is
 * exactly what the app is claiming to do.
 *
 * Degradation: if `SCHEDULE_EXACT_ALARM` is not granted, notify-kit falls
 * back to `setAndAllowWhileIdle` (inexact) rather than dropping the
 * trigger. Timing may drift by minutes; delivery survives.
 */
function buildTrigger(scheduledTime: number): TimestampTrigger {
  return {
    type: TriggerType.TIMESTAMP,
    timestamp: scheduledTime,
    alarmManager: {
      type: AlarmType.SET_ALARM_CLOCK,
    },
  };
}

/**
 * Schedules up to `count` upcoming notifications for a single reminder.
 *
 * Intended for the debug screen (test-one-reminder scenarios). Production
 * paths should call `rescheduleAll`, which uses the global 64-occurrence
 * horizon.
 */
export async function scheduleForReminder(
  reminder: Reminder,
  count = DEFAULT_UPCOMING_COUNT
): Promise<void> {
  if (!reminder.active || count <= 0) {
    return;
  }

  const occurrences = nextOccurrences(reminder, Date.now(), count);

  for (const occurrence of occurrences) {
    const notification = buildNotification(occurrence);
    const trigger = buildTrigger(occurrence.fireAt);
    await notifee.createTriggerNotification(notification, trigger);
  }
}

/**
 * Cancels all scheduled notifications that belong to a given reminder.
 *
 * Matching is based on the deterministic notification ID prefix
 * `${reminderId}-`.
 */
export async function cancelForReminder(reminderId: string): Promise<void> {
  const ids = await notifee.getTriggerNotificationIds();
  const toCancel = ids.filter((id) => id.startsWith(`${reminderId}-`));
  if (toCancel.length > 0) {
    await notifee.cancelTriggerNotifications(toCancel);
  }
}

/**
 * Cancels every scheduled notification. Used by the debug screen.
 * Production reschedules do NOT call this — they use the diff in
 * `rescheduleAll` to avoid tearing down alarms that are still desired.
 */
export async function cancelAll(): Promise<void> {
  await notifee.cancelAllNotifications();
}

/**
 * Refreshes the schedule for the given user using a diff-based algorithm:
 *
 *   1. Load active reminders from the local SQLite repo — every kind, so a
 *      `task` reminder alarms exactly like a `medication` one.
 *   2. Compute the desired 64 soonest occurrences across ALL active reminders
 *      (global selection — see `selectUpcomingOccurrences`).
 *   3. Read the currently-scheduled notification IDs from Notifee.
 *   4. Cancel only the notifications that are no longer in the desired set.
 *   5. Create only the notifications that are missing.
 *
 * The previous implementation called `cancelAll()` on every app foreground
 * and rebuilt every alarm, which was wasteful and caused a churn window
 * where notifications were briefly unarmed. The diff approach is
 * idempotent: two consecutive calls with unchanged data cancel and create
 * nothing.
 *
 * Reading `reminders` rather than `medications` is what makes a `weekly`
 * schedule produce alarms at all — the POC engine had no shape for one.
 */
export async function rescheduleAll(userId: string): Promise<void> {
  const reminders = await remindersRepo.list(userId);

  const desired = selectUpcomingOccurrences(
    reminders,
    Date.now(),
    MAX_GLOBAL_TRIGGERS
  );
  const desiredIds = new Set(
    desired.map((occurrence) =>
      buildNotificationId(occurrence.reminderId, occurrence.fireAt)
    )
  );
  const currentIds = new Set(await notifee.getTriggerNotificationIds());

  const toCancel: string[] = [];
  for (const id of currentIds) {
    if (!desiredIds.has(id)) {
      toCancel.push(id);
    }
  }
  if (toCancel.length > 0) {
    await notifee.cancelTriggerNotifications(toCancel);
  }

  for (const occurrence of desired) {
    const id = buildNotificationId(occurrence.reminderId, occurrence.fireAt);
    if (currentIds.has(id)) {
      continue;
    }
    const notification = buildNotification(occurrence);
    const trigger = buildTrigger(occurrence.fireAt);
    await notifee.createTriggerNotification(notification, trigger);
  }
}

/**
 * Returns the number of pending trigger notifications.
 */
export async function getPendingCount(): Promise<number> {
  const ids = await notifee.getTriggerNotificationIds();
  return ids.length;
}
