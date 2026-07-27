import type { Medication } from "@taskrx/shared";
import { selectUpcomingDoses, upcomingDosesFor } from "@taskrx/shared";
import notifee, {
  AlarmType,
  AndroidImportance,
  type Notification,
  type TimestampTrigger,
  TriggerType,
} from "react-native-notify-kit";

import * as medicationsRepo from "../db/repos/medications";

export const MEDICATION_REMINDER_CHANNEL_ID = "medication_reminders";
export const MEDICATION_REMINDER_CHANNEL_NAME = "Medication Reminders";

/**
 * Per-medication default used by the debug screen when scheduling a single
 * medication in isolation. Production paths go through `rescheduleAll`,
 * which uses `MAX_GLOBAL_TRIGGERS` instead.
 */
const DEFAULT_UPCOMING_COUNT = 10;

/**
 * iOS enforces a hard cap of 64 pending local notifications per app.
 * Android has no cap but 64 is a natural rolling horizon and keeps parity
 * with the future iOS build. Selection is global across all active meds
 * (see `selectUpcomingDoses` in `@taskrx/shared`), not per-medication.
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

export function buildNotificationId(
  medicationId: string,
  scheduledTime: number
): string {
  return `${medicationId}-${String(scheduledTime)}`;
}

function buildNotification(
  med: Medication,
  scheduledTime: number
): Notification {
  const dosageLabel = med.dosage !== undefined ? ` (${med.dosage})` : "";
  return {
    id: buildNotificationId(med.id, scheduledTime),
    title: med.name,
    body: `Time to take${dosageLabel}`,
    data: {
      medicationId: med.id,
      scheduledTime: String(scheduledTime),
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
 * Builds the AlarmManager trigger for a scheduled dose.
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
 * alarm-clock glyph while any trigger is pending. With a 64-dose rolling
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
 * Schedules up to `count` upcoming dose notifications for a single medication.
 *
 * Intended for the debug screen (test-one-med scenarios). Production paths
 * should call `rescheduleAll`, which uses the global 64-dose horizon.
 */
export async function scheduleForMedication(
  med: Medication,
  count = DEFAULT_UPCOMING_COUNT
): Promise<void> {
  if (!med.active || count <= 0) {
    return;
  }

  const doses = upcomingDosesFor(med, Date.now(), count);

  for (const dose of doses) {
    const notification = buildNotification(med, dose.scheduledTime);
    const trigger = buildTrigger(dose.scheduledTime);
    await notifee.createTriggerNotification(notification, trigger);
  }
}

/**
 * Cancels all scheduled notifications that belong to a given medication.
 *
 * Matching is based on the deterministic notification ID prefix
 * `${medicationId}-`.
 */
export async function cancelForMedication(medicationId: string): Promise<void> {
  const ids = await notifee.getTriggerNotificationIds();
  const toCancel = ids.filter((id) => id.startsWith(`${medicationId}-`));
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
 *   1. Load active medications from the local SQLite repo.
 *   2. Compute the desired 64 soonest doses across ALL active meds (global
 *      selection — see `selectUpcomingDoses`).
 *   3. Read the currently-scheduled notification IDs from Notifee.
 *   4. Cancel only the notifications that are no longer in the desired set.
 *   5. Create only the notifications that are missing.
 *
 * The previous implementation called `cancelAll()` on every app foreground
 * and rebuilt every alarm, which was wasteful and caused a churn window
 * where notifications were briefly unarmed. The diff approach is
 * idempotent: two consecutive calls with unchanged data cancel and create
 * nothing.
 */
export async function rescheduleAll(userId: string): Promise<void> {
  const meds = await medicationsRepo.list(userId);
  const medById = new Map<string, Medication>();
  for (const med of meds) {
    medById.set(med.id, med);
  }

  const desired = selectUpcomingDoses(meds, Date.now(), MAX_GLOBAL_TRIGGERS);
  const desiredIds = new Set(
    desired.map((dose) =>
      buildNotificationId(dose.medicationId, dose.scheduledTime)
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

  for (const dose of desired) {
    const id = buildNotificationId(dose.medicationId, dose.scheduledTime);
    if (currentIds.has(id)) {
      continue;
    }
    const med = medById.get(dose.medicationId);
    if (med === undefined) {
      continue;
    }
    const notification = buildNotification(med, dose.scheduledTime);
    const trigger = buildTrigger(dose.scheduledTime);
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
