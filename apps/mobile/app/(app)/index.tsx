import { useUser } from "@clerk/clerk-expo";
import type { InstanceStatus, Reminder, TimelineRow } from "@taskrx/shared";
import { projectTimeline } from "@taskrx/shared";
import { Link, useRouter } from "expo-router";
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

import { SkeletonList } from "../../src/components/Skeleton";
import * as instancesRepo from "../../src/db/repos/reminderInstances";
import * as remindersRepo from "../../src/db/repos/reminders";
import { useUnifiedSync } from "../../src/sync/pull";

/**
 * The Today timeline — the home surface.
 *
 * Every rule about what the day contains lives in `projectTimeline` in
 * `@taskrx/shared`, which is pure and tested there. This file does three things
 * only: pick the window, read the two tables, and render what comes back. If a
 * decision about ordering, deduplication or what counts as "next" ever appears
 * below, it is in the wrong file.
 *
 * Medications only, per issue #10. The projection is already Kind-neutral, so
 * the fusion in #13 widens the read below rather than rewriting anything.
 */

/** Local midnight to local end-of-day, `offset` days from the day holding `now`. */
function dayWindow(
  offset: number,
  now: number
): { readonly start: number; readonly end: number } {
  const base = new Date(now);
  const year = base.getFullYear();
  const month = base.getMonth();
  // `Date` normalizes an out-of-range day, so month and year roll over on their
  // own and no calendar arithmetic is needed here.
  const day = base.getDate() + offset;

  return {
    start: new Date(year, month, day, 0, 0, 0, 0).getTime(),
    end: new Date(year, month, day, 23, 59, 59, 999).getTime(),
  };
}

function dayLabel(offset: number, windowStart: number): string {
  if (offset === 0) return "Today";
  if (offset === -1) return "Yesterday";
  if (offset === 1) return "Tomorrow";

  return new Date(windowStart).toLocaleDateString([], {
    weekday: "short",
    month: "short",
    day: "numeric",
  });
}

function timeLabel(scheduledTime: number): string {
  return new Date(scheduledTime).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit",
  });
}

/**
 * The one place a Kind check on status is allowed.
 *
 * ADR 0001 made the stored vocabulary Kind-neutral precisely so this check
 * happens once, in the UI layer, instead of in every consumer: "a medication is
 * still *shown* as taken".
 */
function statusLabel(status: InstanceStatus, kind: Reminder["kind"]): string {
  switch (status) {
    case "completed":
      return kind === "medication" ? "Taken" : "Done";
    case "skipped":
      return "Skipped";
    case "missed":
      return "Missed";
    case "snoozed":
      return "Snoozed";
    case "pending":
      return "";
  }
}

function TimelineEntry({
  row,
  onPress,
}: {
  readonly row: TimelineRow;
  readonly onPress: () => void;
}): ReactElement {
  const answered = row.status !== "pending";
  const label = statusLabel(row.status, row.kind);

  return (
    <Pressable
      onPress={onPress}
      disabled={answered}
      style={[styles.entry, row.isNext ? styles.entryNext : null]}
      accessibilityRole={answered ? "text" : "button"}
      accessibilityLabel={`${row.name} at ${timeLabel(row.scheduledTime)}${
        label === "" ? "" : `, ${label}`
      }`}
    >
      <View style={styles.entryTimeColumn}>
        <Text style={[styles.entryTime, answered ? styles.muted : null]}>
          {timeLabel(row.scheduledTime)}
        </Text>
        {row.isNext ? <Text style={styles.nextBadge}>NEXT</Text> : null}
      </View>

      <View style={styles.entryBody}>
        <Text style={[styles.entryName, answered ? styles.muted : null]}>
          {row.name}
        </Text>
        {row.dosage !== undefined ? (
          <Text style={styles.entryDosage}>{row.dosage}</Text>
        ) : null}
        {label === "" ? null : (
          <Text style={[styles.entryStatus, styles[`status_${row.status}`]]}>
            {label}
          </Text>
        )}
      </View>
    </Pressable>
  );
}

export default function HomeScreen(): ReactElement {
  const router = useRouter();
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  const [dayOffset, setDayOffset] = useState<number>(0);
  const [rows, setRows] = useState<readonly TimelineRow[]>([]);
  const [windowStart, setWindowStart] = useState<number>(
    () => dayWindow(0, Date.now()).start
  );
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  useUnifiedSync(userId);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setRows([]);
      setLoading(false);
      return;
    }

    const now = Date.now();
    const window = dayWindow(dayOffset, now);
    setWindowStart(window.start);

    try {
      const active = await remindersRepo.list(userId, "medication");
      const instances = await instancesRepo.list({
        userId,
        reminderKind: "medication",
        since: window.start,
        until: window.end,
      });

      // `remindersRepo.list` returns active reminders only, but a stopped
      // medication's recorded doses are still part of the day — the projection
      // drops an instance whose reminder it cannot name, so fetch those.
      const known = new Map(active.map((r) => [r.id, r] as const));
      const missing = [
        ...new Set(
          instances.map((i) => i.reminderId).filter((id) => !known.has(id))
        ),
      ];
      const stopped = await Promise.all(
        missing.map((id) => remindersRepo.get(id))
      );

      setRows(
        projectTimeline({
          reminders: [...active, ...stopped.filter((r) => r !== null)],
          instances,
          window,
          now,
        })
      );
      setError(null);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [userId, dayOffset]);

  useEffect(() => {
    void load();
  }, [load]);

  const onRefresh = useCallback((): void => {
    setRefreshing(true);
    void load();
  }, [load]);

  const openDose = useCallback(
    (row: TimelineRow): void => {
      router.push({
        pathname: "/dose/log",
        params: {
          medicationId: row.reminderId,
          scheduledTime: String(row.scheduledTime),
        },
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
        <Text>Sign in to see your day.</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <View style={styles.dayBar}>
        <Pressable
          hitSlop={12}
          onPress={() => {
            setDayOffset((offset) => offset - 1);
          }}
          accessibilityRole="button"
          accessibilityLabel="Previous day"
        >
          <Text style={styles.dayArrow}>‹</Text>
        </Pressable>

        <Pressable
          hitSlop={8}
          onPress={() => {
            setDayOffset(0);
          }}
          accessibilityRole="button"
          accessibilityLabel="Back to today"
        >
          <Text style={styles.dayLabel}>
            {dayLabel(dayOffset, windowStart)}
          </Text>
        </Pressable>

        <Pressable
          hitSlop={12}
          onPress={() => {
            setDayOffset((offset) => offset + 1);
          }}
          accessibilityRole="button"
          accessibilityLabel="Next day"
        >
          <Text style={styles.dayArrow}>›</Text>
        </Pressable>
      </View>

      {loading ? (
        <SkeletonList rows={5} linesPerRow={2} />
      ) : error !== null ? (
        <View style={styles.center}>
          <Text style={styles.errorText}>Failed to load: {error}</Text>
        </View>
      ) : rows.length === 0 ? (
        <View style={styles.center}>
          <Text style={styles.emptyTitle}>Nothing scheduled</Text>
          <Text style={styles.emptyBody}>
            {dayOffset === 0
              ? "You have no doses due today."
              : `No doses were due ${dayLabel(dayOffset, windowStart).toLowerCase()}.`}
          </Text>
        </View>
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(row) =>
            `${row.reminderId}@${String(row.scheduledTime)}`
          }
          renderItem={({ item }) => (
            <TimelineEntry
              row={item}
              onPress={() => {
                openDose(item);
              }}
            />
          )}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
          }
        />
      )}

      <View style={styles.footer}>
        <Link href="/meds" style={styles.footerLink}>
          Medications
        </Link>
        <Link href="/dose/history" style={styles.footerLink}>
          History
        </Link>
        <Link href="/debug/notifications" style={styles.footerLink}>
          Debug
        </Link>
      </View>
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
  dayArrow: {
    color: "#007aff",
    fontSize: 28,
    fontWeight: "400",
    paddingHorizontal: 8,
  },
  dayBar: {
    alignItems: "center",
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    flexDirection: "row",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 10,
  },
  dayLabel: {
    fontSize: 17,
    fontWeight: "600",
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
  entry: {
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    borderLeftColor: "transparent",
    borderLeftWidth: 3,
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  entryBody: {
    flex: 1,
  },
  entryDosage: {
    color: "#666",
    fontSize: 14,
    marginTop: 2,
  },
  entryName: {
    fontSize: 16,
    fontWeight: "600",
  },
  entryNext: {
    backgroundColor: "#f2f8ff",
    borderLeftColor: "#007aff",
  },
  entryStatus: {
    fontSize: 13,
    fontWeight: "600",
    marginTop: 4,
  },
  entryTime: {
    fontSize: 15,
    fontWeight: "600",
  },
  entryTimeColumn: {
    alignItems: "flex-start",
    width: 84,
  },
  errorText: {
    color: "#c00",
    textAlign: "center",
  },
  footer: {
    borderTopColor: "#eee",
    borderTopWidth: 1,
    flexDirection: "row",
    justifyContent: "space-around",
    paddingVertical: 12,
  },
  footerLink: {
    color: "#007aff",
    fontSize: 15,
    fontWeight: "500",
  },
  listContent: {
    paddingVertical: 4,
  },
  muted: {
    color: "#999",
  },
  nextBadge: {
    color: "#007aff",
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 0.5,
    marginTop: 2,
  },
  status_completed: {
    color: "#137333",
  },
  status_missed: {
    color: "#c00",
  },
  status_pending: {
    color: "#666",
  },
  status_skipped: {
    color: "#8a6d00",
  },
  status_snoozed: {
    color: "#5b3fa8",
  },
});
