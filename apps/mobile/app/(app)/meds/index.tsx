import { useUser } from "@clerk/clerk-expo";
import type { DoseLog, Medication } from "@taskrx/shared";
import { Link, Stack, useRouter } from "expo-router";
import { useCallback, useEffect, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from "react-native";

import { SkeletonList } from "../../../src/components/Skeleton";
import * as doseLogsRepo from "../../../src/db/repos/doseLogs";
import * as medicationsRepo from "../../../src/db/repos/medications";
import { useMedicationsSync } from "../../../src/sync/pull";

function describeSchedule(med: Medication): string {
  if (med.scheduleType === "daily") {
    const times = (med.scheduleTimes as { times: readonly string[] }).times;
    if (times.length === 0) {
      return "Daily (no times set)";
    }
    return `Daily at ${times.join(", ")}`;
  }
  const interval = med.scheduleTimes as { hours: number; startAt: number };
  const start = new Date(interval.startAt);
  const startLabel = `${start.getHours().toString().padStart(2, "0")}:${start.getMinutes().toString().padStart(2, "0")}`;
  return `Every ${String(interval.hours)}h starting ${startLabel}`;
}

function formatLastTaken(doseLog: DoseLog | null): string {
  if (doseLog?.takenAt === undefined) {
    return "Never";
  }
  const date = new Date(doseLog.takenAt);
  return date.toLocaleString([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

function MedicationRow({
  med,
  lastTaken,
  onPress,
}: {
  readonly med: Medication;
  readonly lastTaken: DoseLog | null;
  readonly onPress: () => void;
}): ReactElement {
  return (
    <Pressable onPress={onPress} style={styles.row}>
      <Text style={styles.rowName}>{med.name}</Text>
      {med.dosage !== undefined ? (
        <Text style={styles.rowDosage}>{med.dosage}</Text>
      ) : null}
      <Text style={styles.rowSchedule}>{describeSchedule(med)}</Text>
      <Text style={styles.rowLastTaken}>
        Last taken: {formatLastTaken(lastTaken)}
      </Text>
    </Pressable>
  );
}

export default function MedsListScreen(): ReactElement {
  const router = useRouter();
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;
  const [meds, setMeds] = useState<readonly Medication[]>([]);
  const [lastTakenMap, setLastTakenMap] = useState<
    ReadonlyMap<string, DoseLog | null>
  >(new Map());
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  useMedicationsSync(userId);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setMeds([]);
      setLastTakenMap(new Map());
      setLoading(false);
      return;
    }
    try {
      const rows = await medicationsRepo.selectAll(userId);
      const lastTakenEntries = await Promise.all(
        rows.map(async (med) => {
          const lastTaken = await doseLogsRepo.lastTakenFor(userId, med.id);
          return [med.id, lastTaken] as const;
        })
      );
      const map = new Map<string, DoseLog | null>();
      for (const [medicationId, lastTaken] of lastTakenEntries) {
        map.set(medicationId, lastTaken);
      }

      setMeds(rows);
      setLastTakenMap(map);
      setError(null);
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : "Unknown error";
      setError(message);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [userId]);

  useEffect(() => {
    void load();
  }, [load]);

  const onRefresh = useCallback((): void => {
    setRefreshing(true);
    void load();
  }, [load]);

  const navigateToDoseLog = useCallback(
    (medicationId: string): void => {
      router.push({
        pathname: "/dose/log" as never,
        params: { medicationId, scheduledTime: String(Date.now()) },
      });
    },
    [router]
  );

  if (!isLoaded) {
    return (
      <View style={styles.center}>
        <ActivityIndicator />
      </View>
    );
  }

  if (!isSignedIn || userId === undefined) {
    return (
      <View style={styles.center}>
        <Text>Sign in to view your medications.</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Stack.Screen
        options={{
          title: "My Medications",
          headerRight: () => (
            <Link href="/meds/search" asChild>
              <Pressable hitSlop={8}>
                <Text style={styles.headerAdd}>+ Add</Text>
              </Pressable>
            </Link>
          ),
        }}
      />

      {loading ? (
        <SkeletonList rows={6} linesPerRow={3} />
      ) : error !== null ? (
        <View style={styles.center}>
          <Text style={styles.errorText}>Failed to load: {error}</Text>
        </View>
      ) : meds.length === 0 ? (
        <View style={styles.center}>
          <Text style={styles.emptyTitle}>No medications yet</Text>
          <Text style={styles.emptyBody}>
            Tap + to add your first medication.
          </Text>
        </View>
      ) : (
        <FlatList
          data={meds}
          keyExtractor={(item) => item.id}
          renderItem={({ item }) => (
            <MedicationRow
              med={item}
              lastTaken={lastTakenMap.get(item.id) ?? null}
              onPress={() => {
                navigateToDoseLog(item.id);
              }}
            />
          )}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
          }
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  center: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
  container: {
    flex: 1,
  },
  emptyBody: {
    color: "#666",
    marginTop: 8,
    textAlign: "center",
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: "600",
  },
  errorText: {
    color: "#c00",
    textAlign: "center",
  },
  headerAdd: {
    color: "#007aff",
    fontSize: 16,
    fontWeight: "600",
  },
  listContent: {
    paddingVertical: 8,
  },
  row: {
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  rowDosage: {
    color: "#666",
    fontSize: 14,
    marginTop: 2,
  },
  rowLastTaken: {
    color: "#007aff",
    fontSize: 13,
    marginTop: 4,
  },
  rowName: {
    fontSize: 16,
    fontWeight: "600",
  },
  rowSchedule: {
    color: "#444",
    fontSize: 14,
    marginTop: 4,
  },
});
