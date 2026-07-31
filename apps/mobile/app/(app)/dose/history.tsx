import { useUser } from "@clerk/clerk-expo";
import type { Reminder, ReminderInstance } from "@taskrx/shared";
import { Stack } from "expo-router";
import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  FlatList,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from "react-native";

import { SkeletonList } from "../../../src/components/Skeleton";
import * as instancesRepo from "../../../src/db/repos/reminderInstances";
import * as remindersRepo from "../../../src/db/repos/reminders";
import { useDoseLogsSync } from "../../../src/sync/pull";

interface HistoryItem {
  readonly instance: ReminderInstance;
  readonly medicationName: string;
  readonly dosage: string | undefined;
}

interface DayGroup {
  readonly title: string;
  readonly data: readonly HistoryItem[];
}

/**
 * `snoozed` has no `dose_logs` counterpart — it exists only in the unified
 * `InstanceStatus`, so switching over that union obliges this arm to exist.
 *
 * It does not yet render on device. `dose/log.tsx` now writes instance status
 * directly, but only ever `taken` or `skipped`; `dose-log-mirror.ts` copies
 * `dose_logs.status`, which has no `snoozed` member; and
 * `reminderInstances.markSnoozed` still has no callers — `snooze.ts`
 * reschedules the notification without recording an instance. This arm is
 * therefore forward-looking, and stays unreachable until the snooze path
 * writes status.
 *
 * When it does become reachable it must not be folded into `skipped` or
 * `missed`: the adherence calculator counts those against the user and a
 * snooze is not a miss, so showing it as one would contradict the number on
 * the same screen.
 */
function statusLabel(status: ReminderInstance["status"]): string {
  switch (status) {
    case "taken":
      return "Taken";
    case "snoozed":
      return "Snoozed";
    case "skipped":
      return "Skipped";
    case "missed":
      return "Missed";
    case "pending":
      return "Pending";
  }
}

function statusColor(status: ReminderInstance["status"]): string {
  switch (status) {
    case "taken":
      return "#34c759";
    case "snoozed":
      return "#8e8e93";
    case "skipped":
      return "#ff9500";
    case "missed":
      return "#c00";
    case "pending":
      return "#007aff";
  }
}

function formatTime(date: Date): string {
  return date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function formatDay(date: Date): string {
  const now = new Date();
  const isSameDay =
    date.getDate() === now.getDate() &&
    date.getMonth() === now.getMonth() &&
    date.getFullYear() === now.getFullYear();
  if (isSameDay) {
    return "Today";
  }

  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  const isYesterday =
    date.getDate() === yesterday.getDate() &&
    date.getMonth() === yesterday.getMonth() &&
    date.getFullYear() === yesterday.getFullYear();
  if (isYesterday) {
    return "Yesterday";
  }

  const startOfWeek = new Date(now);
  startOfWeek.setHours(0, 0, 0, 0);
  startOfWeek.setDate(now.getDate() - now.getDay());
  if (date >= startOfWeek) {
    return "This week";
  }

  return date.toLocaleDateString([], { month: "short", day: "numeric" });
}

function groupByDay(items: readonly HistoryItem[]): readonly DayGroup[] {
  const groups = new Map<string, HistoryItem[]>();
  for (const item of items) {
    const day = formatDay(new Date(item.instance.scheduledTime));
    const existing = groups.get(day);
    if (existing === undefined) {
      groups.set(day, [item]);
    } else {
      existing.push(item);
    }
  }
  return Array.from(groups.entries()).map(([title, data]) => ({
    title,
    data,
  }));
}

function HistoryRow({ item }: { readonly item: HistoryItem }): ReactElement {
  const { instance, medicationName, dosage } = item;
  const scheduled = new Date(instance.scheduledTime);
  const takenAt =
    instance.takenAt !== undefined ? new Date(instance.takenAt) : null;

  return (
    <View style={styles.row}>
      <View style={styles.rowHeader}>
        <Text style={styles.rowName}>{medicationName}</Text>
        <Text
          style={[styles.rowStatus, { color: statusColor(instance.status) }]}
        >
          {statusLabel(instance.status)}
        </Text>
      </View>
      {dosage !== undefined ? (
        <Text style={styles.rowDosage}>{dosage}</Text>
      ) : null}
      <Text style={styles.rowTime}>Scheduled: {formatTime(scheduled)}</Text>
      {takenAt !== null ? (
        <Text style={styles.rowTime}>Taken: {formatTime(takenAt)}</Text>
      ) : null}
      {instance.notes !== undefined && instance.notes.length > 0 ? (
        <Text style={styles.rowNotes}>{instance.notes}</Text>
      ) : null}
    </View>
  );
}

function SectionHeader({ title }: { readonly title: string }): ReactElement {
  return (
    <View style={styles.sectionHeader}>
      <Text style={styles.sectionTitle}>{title}</Text>
    </View>
  );
}

export default function DoseHistoryScreen(): ReactElement {
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  useDoseLogsSync(userId);

  const [items, setItems] = useState<readonly HistoryItem[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setItems([]);
      setLoading(false);
      return;
    }
    try {
      const [instances, medications] = await Promise.all([
        instancesRepo.list({ userId }),
        remindersRepo.list(userId, "medication"),
      ]);
      const medMap = new Map<string, Reminder>();
      for (const med of medications) {
        medMap.set(med.id, med);
      }

      const enriched: HistoryItem[] = instances.map((instance) => {
        const med = medMap.get(instance.reminderId);
        return {
          instance,
          medicationName: med?.name ?? "Unknown medication",
          dosage: med?.dosage,
        };
      });

      setItems(enriched);
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

  const grouped = useMemo(() => groupByDay(items), [items]);

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
        <Text>Sign in to view dose history.</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Stack.Screen options={{ title: "Dose History" }} />

      {loading ? (
        <SkeletonList rows={8} linesPerRow={4} />
      ) : error !== null ? (
        <View style={styles.center}>
          <Text style={styles.errorText}>Failed to load: {error}</Text>
        </View>
      ) : items.length === 0 ? (
        <View style={styles.center}>
          <Text style={styles.emptyTitle}>No doses logged yet</Text>
          <Text style={styles.emptyBody}>
            Doses you mark taken or skipped will appear here.
          </Text>
        </View>
      ) : (
        <FlatList
          data={grouped}
          keyExtractor={(group) => group.title}
          renderItem={({ item: group }) => (
            <View>
              <SectionHeader title={group.title} />
              {group.data.map((item) => (
                <HistoryRow key={item.instance.id} item={item} />
              ))}
            </View>
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
  rowHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  rowName: {
    flex: 1,
    fontSize: 16,
    fontWeight: "600",
  },
  rowNotes: {
    color: "#666",
    fontSize: 13,
    fontStyle: "italic",
    marginTop: 4,
  },
  rowStatus: {
    fontSize: 14,
    fontWeight: "600",
  },
  rowTime: {
    color: "#444",
    fontSize: 14,
    marginTop: 4,
  },
  sectionHeader: {
    backgroundColor: "#f5f5f5",
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  sectionTitle: {
    color: "#444",
    fontSize: 14,
    fontWeight: "700",
    textTransform: "uppercase",
  },
});
