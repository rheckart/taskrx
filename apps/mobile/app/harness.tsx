import { useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useRef, useState } from "react";
import type { ReactElement } from "react";
import { StyleSheet, Text, View } from "react-native";
import notifee, {
  AlarmType,
  AndroidImportance,
  TriggerType,
  type TimestampTrigger,
} from "react-native-notify-kit";

import {
  MEDICATION_REMINDER_CHANNEL_ID,
  cancelAll,
  createNotificationChannel,
} from "../src/notifications";
import { checkExactAlarmPermission } from "../src/notifications/permissions";

/**
 * Hidden harness route — not linked from any UI, only invoked by the adb
 * notification harness via `am start -a android.intent.action.VIEW -d
 * "taskrx://harness?action=..."`. Emits `[harness]` log lines that the
 * bash script greps out of `adb logcat`.
 *
 * Auth is deliberately bypassed for this route (see app/_layout.tsx
 * AuthRedirectGate) so the harness can run against a fresh install
 * without needing to sign in first — the notification pipeline is
 * fully local and does not require Convex/Clerk.
 */

type HarnessAction =
  | "schedule"
  | "cancel-all"
  | "pending-count"
  | "pending-ids"
  | "exact-alarm-status";

interface HarnessParams {
  readonly action: HarnessAction;
  readonly id: string;
  readonly delayMs: number;
  readonly title: string;
  readonly body: string;
}

function parseParams(
  raw: Record<string, string | string[] | undefined>
): HarnessParams {
  const pick = (key: string, fallback: string): string => {
    const value = raw[key];
    if (Array.isArray(value)) {
      return value[0] ?? fallback;
    }
    return value ?? fallback;
  };

  const action = pick("action", "pending-count") as HarnessAction;
  const id = pick("id", `harness-${String(Date.now())}`);
  const delayMsRaw = pick("delayMs", "90000");
  const delayMs = Math.max(1000, Number.parseInt(delayMsRaw, 10));
  const title = pick("title", "Harness test");
  const body = pick("body", "Scheduled by the notification harness");

  return { action, id, delayMs, title, body };
}

async function runSchedule(params: HarnessParams): Promise<string> {
  await createNotificationChannel();
  const scheduledTime = Date.now() + params.delayMs;
  const trigger: TimestampTrigger = {
    type: TriggerType.TIMESTAMP,
    timestamp: scheduledTime,
    alarmManager: {
      type: AlarmType.SET_ALARM_CLOCK,
    },
  };
  await notifee.createTriggerNotification(
    {
      id: params.id,
      title: params.title,
      body: params.body,
      data: {
        medicationId: params.id,
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
  return `scheduled id=${params.id} at=${String(scheduledTime)}`;
}

async function runCancelAll(): Promise<string> {
  await cancelAll();
  return "cancelled all trigger notifications";
}

async function runPendingCount(): Promise<string> {
  const ids = await notifee.getTriggerNotificationIds();
  return `pending_count=${String(ids.length)}`;
}

async function runPendingIds(): Promise<string> {
  const ids = await notifee.getTriggerNotificationIds();
  return `pending_ids=${ids.join(",")}`;
}

async function runExactAlarmStatus(): Promise<string> {
  const granted = await checkExactAlarmPermission();
  return `exact_alarm_granted=${String(granted)}`;
}

async function dispatch(params: HarnessParams): Promise<string> {
  switch (params.action) {
    case "schedule":
      return runSchedule(params);
    case "cancel-all":
      return runCancelAll();
    case "pending-count":
      return runPendingCount();
    case "pending-ids":
      return runPendingIds();
    case "exact-alarm-status":
      return runExactAlarmStatus();
    default:
      return `unknown_action=${String(params.action)}`;
  }
}

export default function HarnessRoute(): ReactElement {
  const rawParams = useLocalSearchParams();
  const router = useRouter();
  const dispatchedRef = useRef(false);
  const [result, setResult] = useState<string>("running...");

  useEffect(() => {
    if (dispatchedRef.current) {
      return;
    }
    dispatchedRef.current = true;

    const params = parseParams(rawParams);
    console.log(
      `[harness] action=${params.action} params=${JSON.stringify(params)}`
    );

    void dispatch(params)
      .then((message) => {
        console.log(`[harness] result: ${message}`);
        setResult(message);
      })
      .catch((error: unknown) => {
        const description =
          error instanceof Error ? error.message : String(error);
        console.log(`[harness] error: ${description}`);
        setResult(`error: ${description}`);
      });
  }, [rawParams, router]);

  return (
    <View style={styles.container}>
      <Text style={styles.text}>{result}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    padding: 16,
  },
  text: {
    fontFamily: "monospace",
    fontSize: 14,
  },
});
