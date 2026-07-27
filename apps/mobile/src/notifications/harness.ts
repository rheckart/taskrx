import notifee, {
  AlarmType,
  AndroidImportance,
  TriggerType,
  type TimestampTrigger,
} from "react-native-notify-kit";

import { checkExactAlarmPermission } from "./permissions";

import {
  MEDICATION_REMINDER_CHANNEL_ID,
  cancelAll,
  createNotificationChannel,
} from "./index";

/**
 * Action layer for the adb-driven notification harness.
 *
 * Parameters arrive as PATH SEGMENTS rather than a query string because
 * expo-router does not match `taskrx://harness?a=b` deep links on a custom
 * scheme — only the path form routes reliably. See
 * `apps/mobile/scripts/notification-harness.sh`.
 */

export type HarnessAction =
  | "schedule"
  | "cancel-all"
  | "pending-count"
  | "pending-ids"
  | "exact-alarm-status";

export interface HarnessSpec {
  readonly action: HarnessAction;
  readonly id: string;
  readonly delayMs: number;
  readonly nonce: string;
}

const VALID_ACTIONS: readonly HarnessAction[] = [
  "schedule",
  "cancel-all",
  "pending-count",
  "pending-ids",
  "exact-alarm-status",
];

function isHarnessAction(value: string): value is HarnessAction {
  return (VALID_ACTIONS as readonly string[]).includes(value);
}

/**
 * Parses `[action, id, delayMs, nonce]` path segments, tolerating any
 * missing tail segments.
 */
export function parseHarnessSpec(segments: readonly string[]): HarnessSpec {
  const rawAction = segments[0] ?? "pending-count";
  const action: HarnessAction = isHarnessAction(rawAction)
    ? rawAction
    : "pending-count";
  const id = segments[1] ?? `harness-${String(Date.now())}`;
  const parsedDelay = Number.parseInt(segments[2] ?? "90000", 10);
  const delayMs = Number.isNaN(parsedDelay)
    ? 90000
    : Math.max(1000, parsedDelay);
  const nonce = segments[3] ?? "";
  return { action, id, delayMs, nonce };
}

async function runSchedule(spec: HarnessSpec): Promise<string> {
  await createNotificationChannel();
  const scheduledTime = Date.now() + spec.delayMs;
  const trigger: TimestampTrigger = {
    type: TriggerType.TIMESTAMP,
    timestamp: scheduledTime,
    alarmManager: { type: AlarmType.SET_ALARM_CLOCK },
  };
  await notifee.createTriggerNotification(
    {
      id: spec.id,
      title: `Harness ${spec.id}`,
      body: "Scheduled by the notification harness",
      data: {
        medicationId: spec.id,
        scheduledTime: String(scheduledTime),
        harness: "true",
      },
      android: {
        channelId: MEDICATION_REMINDER_CHANNEL_ID,
        smallIcon: "ic_launcher",
        pressAction: { id: "mark-taken", launchActivity: "default" },
        importance: AndroidImportance.HIGH,
        autoCancel: true,
      },
    },
    trigger
  );
  return `scheduled id=${spec.id} at=${String(scheduledTime)} delayMs=${String(spec.delayMs)}`;
}

export async function runHarnessAction(spec: HarnessSpec): Promise<string> {
  switch (spec.action) {
    case "schedule":
      return runSchedule(spec);
    case "cancel-all": {
      await cancelAll();
      return "cancelled all trigger notifications";
    }
    case "pending-count": {
      const ids = await notifee.getTriggerNotificationIds();
      return `pending_count=${String(ids.length)}`;
    }
    case "pending-ids": {
      const ids = await notifee.getTriggerNotificationIds();
      return `pending_ids=${ids.join(",")}`;
    }
    case "exact-alarm-status": {
      const granted = await checkExactAlarmPermission();
      return `exact_alarm_granted=${String(granted)}`;
    }
    default:
      return `unknown_action=${String(spec.action)}`;
  }
}
