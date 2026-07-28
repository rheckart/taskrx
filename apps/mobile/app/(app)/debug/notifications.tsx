import { useUser } from "@clerk/clerk-expo";
import { createReminder, intervalTimes } from "@taskrx/shared";
import { Stack } from "expo-router";
import { useCallback, useEffect, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  Button,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from "react-native";

import {
  cancelAll,
  checkNotificationPermission,
  createNotificationChannel,
  getPendingCount,
  MEDICATION_REMINDER_CHANNEL_ID,
  requestPermissions,
  rescheduleAll,
  scheduleForReminder,
} from "../../../src/notifications";
import type { PermissionResult } from "../../../src/notifications";

function PermissionRow({
  label,
  value,
}: {
  readonly label: string;
  readonly value: string;
}): ReactElement {
  return (
    <View style={styles.row}>
      <Text style={styles.rowLabel}>{label}</Text>
      <Text style={styles.rowValue}>{value}</Text>
    </View>
  );
}

export default function NotificationsDebugScreen(): ReactElement {
  const { user } = useUser();
  const userId = user?.id;

  const [permission, setPermission] = useState<PermissionResult | null>(null);
  const [pendingCount, setPendingCount] = useState<number | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(async (): Promise<void> => {
    setLoading(true);
    try {
      const [perm, count] = await Promise.all([
        checkNotificationPermission(),
        getPendingCount(),
      ]);
      setPermission(perm);
      setPendingCount(count);
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Refresh failed: ${text}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void createNotificationChannel().catch(() => {
      // Channel may already exist; ignore.
    });
    void refresh();
  }, [refresh]);

  const handleRequestPermissions = async (): Promise<void> => {
    setLoading(true);
    try {
      const result = await requestPermissions();
      setPermission(result);
      setMessage(`Permission status: ${result.status}`);
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Permission request failed: ${text}`);
    } finally {
      setLoading(false);
    }
  };

  const handleTestSchedule = async (): Promise<void> => {
    if (userId === undefined) {
      setMessage("Sign in to test scheduling.");
      return;
    }
    setLoading(true);
    try {
      const scheduledTime = Date.now() + 30_000;
      await scheduleForReminder(
        createReminder({
          id: "debug-med",
          userId,
          kind: "medication",
          name: "Debug Medication",
          dosage: "1 tablet",
          schedule: intervalTimes(24, scheduledTime),
          createdAt: Date.now(),
          updatedAt: Date.now(),
        }),
        1
      );
      setMessage(
        `Test notification scheduled for ${new Date(scheduledTime).toLocaleTimeString()}`
      );
      await refresh();
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Test schedule failed: ${text}`);
    } finally {
      setLoading(false);
    }
  };

  const handleCancelAll = async (): Promise<void> => {
    setLoading(true);
    try {
      await cancelAll();
      setMessage("All notifications cancelled.");
      await refresh();
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Cancel failed: ${text}`);
    } finally {
      setLoading(false);
    }
  };

  const handleRescheduleAll = async (): Promise<void> => {
    if (userId === undefined) {
      setMessage("Sign in to reschedule.");
      return;
    }
    setLoading(true);
    try {
      await rescheduleAll(userId);
      setMessage("Rescheduled all active medications.");
      await refresh();
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Reschedule failed: ${text}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Stack.Screen options={{ title: "Notification Debug" }} />

      <Text style={styles.title}>Notification Debug</Text>

      {loading ? <ActivityIndicator style={styles.loader} /> : null}

      <View style={styles.card}>
        <PermissionRow
          label="Notification permission"
          value={permission?.status ?? "unknown"}
        />
        <PermissionRow
          label="Exact alarm granted"
          value={permission?.exactAlarmGranted ? "yes" : "no"}
        />
        <PermissionRow
          label="Pending notifications"
          value={pendingCount === null ? "unknown" : String(pendingCount)}
        />
        <PermissionRow
          label="Channel ID"
          value={MEDICATION_REMINDER_CHANNEL_ID}
        />
      </View>

      <View style={styles.buttonGroup}>
        <Button
          title="Request permissions"
          onPress={() => void handleRequestPermissions()}
        />
        <Button
          title="Test schedule (in 30s)"
          onPress={() => void handleTestSchedule()}
        />
        <Button title="Cancel all" onPress={() => void handleCancelAll()} />
        <Button
          title="Reschedule all"
          onPress={() => void handleRescheduleAll()}
        />
      </View>

      {message !== null ? <Text style={styles.message}>{message}</Text> : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  buttonGroup: {
    gap: 12,
    marginTop: 16,
    width: "100%",
  },
  card: {
    backgroundColor: "#f5f5f5",
    borderRadius: 8,
    padding: 16,
    width: "100%",
  },
  container: {
    alignItems: "center",
    padding: 24,
  },
  loader: {
    marginVertical: 12,
  },
  message: {
    color: "#444",
    marginTop: 16,
    textAlign: "center",
  },
  row: {
    flexDirection: "row",
    justifyContent: "space-between",
    paddingVertical: 8,
  },
  rowLabel: {
    color: "#444",
    fontSize: 14,
  },
  rowValue: {
    fontSize: 14,
    fontWeight: "500",
  },
  title: {
    fontSize: 22,
    fontWeight: "700",
    marginBottom: 16,
  },
});
