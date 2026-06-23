import { useUser } from "@clerk/clerk-expo";
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

import * as medicationsRepo from "../../../src/db/repos/medications";
import { getPendingCount, rescheduleAll } from "../../../src/notifications";

function padTwo(value: number): string {
  return value.toString().padStart(2, "0");
}

function buildStaggeredDailyTimes(now: Date): string[] {
  const baseMinute = now.getMinutes() + 1;
  const carryHours = Math.floor(baseMinute / 60);
  const minute = baseMinute % 60;
  const baseHour = now.getHours() + carryHours;

  const times: string[] = [];
  for (let i = 0; i < 30; i += 1) {
    const hour = (baseHour + i) % 24;
    times.push(`${padTwo(hour)}:${padTwo(minute)}`);
  }
  return times;
}

export default function StressSeedDebugScreen(): ReactElement {
  const { user } = useUser();
  const userId = user?.id;

  const [medCount, setMedCount] = useState<number | null>(null);
  const [pendingCount, setPendingCount] = useState<number | null>(null);
  const [loading, setLoading] = useState<boolean>(false);
  const [message, setMessage] = useState<string | null>(null);

  const refresh = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setMedCount(null);
      setPendingCount(null);
      return;
    }
    setLoading(true);
    try {
      const [meds, count] = await Promise.all([
        medicationsRepo.list(userId),
        getPendingCount(),
      ]);
      setMedCount(meds.length);
      setPendingCount(count);
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Refresh failed: ${text}`);
    } finally {
      setLoading(false);
    }
  }, [userId]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleSeed = async (): Promise<void> => {
    if (userId === undefined) {
      setMessage("Sign in to seed stress-test data.");
      return;
    }
    setLoading(true);
    try {
      const times = buildStaggeredDailyTimes(new Date());
      for (let i = 0; i < 30; i += 1) {
        await medicationsRepo.create({
          userId,
          name: `Stress Test Med ${String(i + 1)}`,
          dosage: "1 tablet",
          scheduleType: "daily",
          scheduleTimes: { times: [times[i] ?? "09:00"] },
        });
      }
      await rescheduleAll(userId);
      const count = await getPendingCount();
      setMessage(
        `Seeded 30 medications. Pending notifications: ${String(count)}`
      );
      await refresh();
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Seed failed: ${text}`);
    } finally {
      setLoading(false);
    }
  };

  const handleClear = async (): Promise<void> => {
    if (userId === undefined) {
      setMessage("Sign in to clear stress-test data.");
      return;
    }
    setLoading(true);
    try {
      const meds = await medicationsRepo.list(userId);
      const testMeds = meds.filter((med) =>
        med.name.startsWith("Stress Test Med")
      );
      for (const med of testMeds) {
        await medicationsRepo.softDelete(med.id);
      }
      await rescheduleAll(userId);
      const count = await getPendingCount();
      setMessage(
        `Cleared ${String(testMeds.length)} test medications. Pending notifications: ${String(count)}`
      );
      await refresh();
    } catch (err: unknown) {
      const text = err instanceof Error ? err.message : "Unknown error";
      setMessage(`Clear failed: ${text}`);
    } finally {
      setLoading(false);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <Stack.Screen options={{ title: "Stress Test Seed" }} />

      <Text style={styles.title}>Stress Test Seed</Text>

      {loading ? <ActivityIndicator style={styles.loader} /> : null}

      <View style={styles.card}>
        <View style={styles.row}>
          <Text style={styles.rowLabel}>Active medications</Text>
          <Text style={styles.rowValue}>
            {medCount === null ? "unknown" : String(medCount)}
          </Text>
        </View>
        <View style={styles.row}>
          <Text style={styles.rowLabel}>Pending notifications</Text>
          <Text style={styles.rowValue}>
            {pendingCount === null ? "unknown" : String(pendingCount)}
          </Text>
        </View>
      </View>

      <View style={styles.buttonGroup}>
        <Button title="Seed 30 meds" onPress={() => void handleSeed()} />
        <Button
          title="Clear All Test Meds"
          onPress={() => void handleClear()}
        />
        <Button title="Refresh counts" onPress={() => void refresh()} />
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
