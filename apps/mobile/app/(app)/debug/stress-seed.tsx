import { useUser } from "@clerk/clerk-expo";
import { dailyTimes } from "@taskrx/shared";
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

import * as remindersRepo from "../../../src/db/repos/reminders";
import { getPendingCount, rescheduleAll } from "../../../src/notifications";

/**
 * Seeds through `remindersRepo`, the same write path `meds/new.tsx` uses.
 *
 * That matters for what this screen is for: `docs/poc-stress-tests.md` drives
 * it to prove the 64-alarm horizon holds under 30 active medications, and
 * `rescheduleAll` selects that horizon from `reminders`. Seeding the legacy
 * table and relying on the mirror to project it would leave the stress case
 * measuring the projection rather than the path a person's medication now
 * takes.
 *
 * `kind: "medication"` throughout, so the seeded rows count as medications on
 * the list screen and against the medication Tier, exactly as before.
 *
 * One gap this leaves, and it only bites a device seeded by a pre-cutover
 * build: "Clear All Test Meds" now soft-deletes reminders only, so a legacy
 * `medications` row survives it. That is enough to stop the alarms — the
 * scheduler selects from `reminders` — but it is not durable while signed in.
 * `useMedicationsSync` on the medications list hands `api.medications.list`
 * back to `mirrorMedicationRow`, whose conflict clause writes
 * `active = excluded.active`, so the server's still-active copy re-activates
 * the projection and the alarms return. Clearing the legacy rows from here
 * would mean re-introducing a `medications` write to a screen, which is the
 * direction this ticket exists to reverse; wiping app storage is the honest
 * reset, and the whole problem goes when `medications` is retired.
 */

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
        remindersRepo.list(userId, "medication"),
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
        await remindersRepo.create({
          userId,
          kind: "medication",
          name: `Stress Test Med ${String(i + 1)}`,
          dosage: "1 tablet",
          schedule: dailyTimes([times[i] ?? "09:00"]),
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
      const meds = await remindersRepo.list(userId, "medication");
      const testMeds = meds.filter((med) =>
        med.name.startsWith("Stress Test Med")
      );
      for (const med of testMeds) {
        await remindersRepo.softDelete(med.id);
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
