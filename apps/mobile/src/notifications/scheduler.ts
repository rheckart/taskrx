import notifee, {
  AndroidImportance,
  type Notification,
  type TimestampTrigger,
  TriggerType,
  AlarmType,
} from "@notifee/react-native";
import type { Medication } from "@taskrx/shared";
import { upcomingDosesFor } from "@taskrx/shared";

import * as medicationsRepo from "../db/repos/medications.js";

export const MEDICATION_REMINDER_CHANNEL_ID = "medication_reminders";
export const MEDICATION_REMINDER_CHANNEL_NAME = "Medication Reminders";

const DEFAULT_UPCOMING_COUNT = 10;

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

function buildTrigger(scheduledTime: number): TimestampTrigger {
  return {
    type: TriggerType.TIMESTAMP,
    timestamp: scheduledTime,
    alarmManager: {
      type: AlarmType.SET_EXACT_AND_ALLOW_WHILE_IDLE,
    },
  };
}

/**
 * Schedules up to `count` upcoming dose notifications for a single medication.
 *
 * Defaults to the next 10 doses. Uses the shared schedule engine to compute
 * dose times, then maps each to a Notifee TriggerNotification with an exact
 * AlarmManager trigger so it survives Doze mode.
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
 * Cancels all scheduled notifications across all medications.
 */
export async function cancelAll(): Promise<void> {
  await notifee.cancelAllNotifications();
}

/**
 * Recomputes upcoming doses for all active medications and schedules them.
 *
 * This is the main entry point for refreshing the notification schedule:
 *   1. Cancel every pending trigger notification.
 *   2. Load active meds from the local SQLite repo.
 *   3. Schedule the next N doses for each med.
 */
export async function rescheduleAll(userId: string): Promise<void> {
  await cancelAll();
  const meds = await medicationsRepo.list(userId);
  for (const med of meds) {
    await scheduleForMedication(med, DEFAULT_UPCOMING_COUNT);
  }
}

/**
 * Returns the number of pending trigger notifications.
 */
export async function getPendingCount(): Promise<number> {
  const ids = await notifee.getTriggerNotificationIds();
  return ids.length;
}
