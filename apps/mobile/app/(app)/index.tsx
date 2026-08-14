import { useClerk, useUser } from "@clerk/clerk-expo";
import type { Reminder, TimelineRow } from "@taskrx/shared";
import {
  isAnswerable,
  localDayBounds,
  projectTimeline,
  timelineRowKey,
} from "@taskrx/shared";
import { Link, useFocusEffect, useRouter } from "expo-router";
import { useCallback, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  Button,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from "react-native";

import { NotificationPermissionBanner } from "../../src/components/NotificationPermissionBanner";
import { SkeletonList } from "../../src/components/Skeleton";
import * as instancesRepo from "../../src/db/repos/reminderInstances";
import * as remindersRepo from "../../src/db/repos/reminders";
import { runMissedSweep } from "../../src/sweep/runner";
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
 * Both Kinds, as of issue #13 — the fusion this whole sequence exists for. It
 * cost two lines, because #10 wrote the projection Kind-neutral and left the
 * narrowing in the read; taking the two `"medication"` filters off the queries
 * below is the entire change to what the day contains. "Take the 09:00 dose,
 * then call the pharmacy at 10:00" is now one list.
 *
 * The one thing it does beyond read-and-render is run the missed sweep, so that
 * a moment which has run out of time reads as Missed here rather than as still
 * waiting for an answer. That is not a decision either — `runMissedSweep` wraps
 * a pure planner — only an ordering, and the ordering is deliberate: the day is
 * rendered first and re-projected after, so a long absence never leaves someone
 * looking at a skeleton while hundreds of rows are written.
 */

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
 * still *shown* as taken". It takes the whole row rather than a status and a
 * kind, because two of its five arms now need a third field and a growing
 * parameter list of a row's parts is a row.
 */
function statusLabel(row: TimelineRow): string {
  switch (row.status) {
    case "completed":
      return row.kind === "medication" ? "Taken" : "Done";
    case "skipped":
      return "Skipped";
    case "missed":
      return "Missed";
    case "snoozed":
      // Named with its new moment, because that moment is the whole content of
      // a snooze: it is when the reminder comes back, on either Tier since #14
      // gave the task Tier a trigger. An unqualified "Snoozed" would leave the
      // person no way to see what they had decided.
      return row.snoozedUntil === undefined
        ? "Snoozed"
        : `Snoozed until ${timeLabel(row.snoozedUntil)}`;
    case "pending":
      return "";
  }
}

/**
 * Both survive from the stub this screen replaced. Sign-out especially: it is
 * the app's only route out of an account, and the timeline taking over the home
 * surface is no reason to strand someone in one.
 */
function SignOutButton(): ReactElement {
  const { signOut } = useClerk();

  return <Button onPress={() => void signOut()} title="Sign out" />;
}

function UserBadge(): ReactElement | null {
  const { isLoaded, user } = useUser();

  if (!isLoaded || user === null) return null;

  const email = user.primaryEmailAddress?.emailAddress;
  const displayName =
    user.fullName ?? user.username ?? user.firstName ?? email ?? "User";

  return (
    <Text numberOfLines={1} style={styles.accountName}>
      {displayName}
    </Text>
  );
}

/**
 * "A dose and a task are distinguishable at a glance."
 *
 * Three signals rather than one, because a single one always fails somebody. A
 * coloured bar down the left edge carries a fast scan; the word beside the name
 * carries a slow read and a colour-blind one; and the same word is spoken into
 * the accessibility label, which is the only channel a screen reader has.
 *
 * Deliberately not colour alone, and deliberately not an icon alone: the two
 * things being told apart are "call the pharmacy" and a medication a person's
 * treatment depends on, so the distinction has to survive a glance in poor
 * light as well as a careful look.
 */
function kindLabel(kind: Reminder["kind"]): string {
  return kind === "medication" ? "Medication" : "Task";
}

function TimelineRowView({
  row,
  onPress,
}: {
  readonly row: TimelineRow;
  readonly onPress: () => void;
}): ReactElement {
  // Asked of `@taskrx/shared` rather than decided here, because the on-device
  // lookup this row's tap lands in asks the same question. A row this offers
  // and that refuses is a button that does nothing. The rule itself — that a
  // snooze is a deferral rather than an answer, so the moment stays open — is
  // stated with `ANSWERABLE_STATUSES`.
  const answered = !isAnswerable(row.status);
  const label = statusLabel(row);
  const kind = kindLabel(row.kind);

  return (
    <Pressable
      onPress={onPress}
      disabled={answered}
      style={[
        styles.entry,
        row.kind === "medication" ? styles.entryMedication : styles.entryTask,
        row.isNext ? styles.entryNext : null,
      ]}
      accessibilityRole={answered ? "text" : "button"}
      accessibilityLabel={`${kind}: ${row.name} at ${timeLabel(
        row.scheduledTime
      )}${label === "" ? "" : `, ${label}`}`}
    >
      <View style={styles.entryTimeColumn}>
        <Text style={[styles.entryTime, answered ? styles.muted : null]}>
          {timeLabel(row.scheduledTime)}
        </Text>
        {row.isNext ? <Text style={styles.nextBadge}>NEXT</Text> : null}
      </View>

      <View style={styles.entryBody}>
        <View style={styles.entryNameRow}>
          <Text style={[styles.entryName, answered ? styles.muted : null]}>
            {row.name}
          </Text>
          <Text
            style={[
              styles.kindTag,
              row.kind === "medication"
                ? styles.kindTagMed
                : styles.kindTagTask,
            ]}
          >
            {kind}
          </Text>
        </View>
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
    () => localDayBounds(Date.now(), 0).start
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
    // Was a `dayWindow` helper here, which computed the same local-day span as
    // the adherence period did and sat in a screen where no test could reach it.
    // A `TimelineWindow` is structurally these bounds, so the projection takes
    // them unchanged.
    const window = localDayBounds(now, dayOffset);
    setWindowStart(window.start);

    const project = async (): Promise<void> => {
      // No `kind` argument on either read: the day is both Kinds interleaved,
      // and which of them a row is belongs to how it renders, not to whether it
      // belongs here at all.
      //
      // `includeInactive` because a stopped reminder's recorded Instances are
      // still part of the day. Which of these predict new Occurrences is the
      // projection's decision, not this screen's — it skips inactive ones.
      const reminders = await remindersRepo.list(userId, undefined, {
        includeInactive: true,
      });
      const instances = await instancesRepo.list({
        userId,
        since: window.start,
        until: window.end,
      });

      setRows(projectTimeline({ reminders, instances, window, now }));
      setError(null);
    };

    try {
      await project();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }

    // The sweep runs AFTER the day is on screen, and the day is re-projected
    // only if it recorded something.
    //
    // Ordering it first would have been simpler to read, and wrong: someone
    // coming back after a week away has a sweep with hundreds of rows to write,
    // each its own insert and sync-queue entry, and the timeline would sit on a
    // skeleton until all of them landed — against the criterion that opening the
    // app after a long absence stays responsive. Rows the sweep settles were
    // rendered a moment ago as `pending`, which is what they were.
    //
    // `SweepBootstrap` runs it on foreground too; the two are serialized, so
    // whichever arrives second finds nothing to do.
    try {
      if ((await runMissedSweep(userId, now)) > 0) {
        await project();
      }
    } catch (err: unknown) {
      // Not surfaced: the timeline above is correct either way, and the sweep
      // runs again on the next foreground.
      console.error("[sweep] sweep after timeline read failed:", err);
    }
  }, [userId, dayOffset]);

  /**
   * On focus, not on mount.
   *
   * Answering an Occurrence leaves this screen, writes the Instance, and comes
   * back. A mount-only read would show the row still `pending` until a manual
   * pull-to-refresh, so "something already handled stays visible in its handled
   * state" would hold in the projection and not in the app. This also picks up
   * rows a background pull merged while the screen was away.
   */
  useFocusEffect(
    useCallback(() => {
      void load();
    }, [load])
  );

  const onRefresh = useCallback((): void => {
    setRefreshing(true);
    void load();
  }, [load]);

  const openAnswer = useCallback(
    (row: TimelineRow): void => {
      router.push({
        // One destination for both Kinds — that screen is Kind-aware, and a
        // second one would be two places to keep the answer rules in step. The
        // route path and the `medicationId` param are both frozen names; see
        // the note at the top of `dose/log.tsx` for what they are frozen by.
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
      {/*
        Above the day, not inside it. The banner's subject is whether ANY of the
        rows below can reach the person at all — issue #20's silent state, where
        every alarm fires and Android discards every notification — so it cannot
        sit among them as though it were one more thing due at a time. It decides
        nothing itself and renders nothing when reminders can arrive.
      */}
      <NotificationPermissionBanner />

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

        {/*
          Forward only as far as today. The ticket asks that "a previous day can
          be viewed" and says nothing about future ones, and on a future day
          every row is ahead of `now`, so the first would be badged NEXT — which
          reads as "do this now" about something not due for a day.
        */}
        <Pressable
          hitSlop={12}
          disabled={dayOffset >= 0}
          onPress={() => {
            setDayOffset((offset) => Math.min(0, offset + 1));
          }}
          accessibilityRole="button"
          accessibilityLabel="Next day"
        >
          <Text
            style={[
              styles.dayArrow,
              dayOffset >= 0 ? styles.dayArrowOff : null,
            ]}
          >
            ›
          </Text>
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
              ? "No medications or tasks due today."
              : `You had nothing due ${dayLabel(dayOffset, windowStart).toLowerCase()}.`}
          </Text>
        </View>
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(row) =>
            timelineRowKey(row.reminderId, row.scheduledTime)
          }
          renderItem={({ item }) => (
            <TimelineRowView
              row={item}
              onPress={() => {
                openAnswer(item);
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
        {/*
          First, filled, and the only one that says "+" for both Kinds. The spec
          asks that natural-language entry be "the primary creation surface for
          both Kinds", and first-among-seven-identical-text-links is not that —
          it is one more link in a row of them. It gets the button, so the thing
          a person opens this app to do reads as the thing to press.

          The two hand-entry forms stay reachable rather than being retired
          here: this screen falls back to a form of its own for a phrase it
          cannot read, but "+ Task" is still the shorter road when there was
          never a phrase to type, and taking a working surface away is not this
          ticket's to do.
        */}
        <Link href="/add" style={[styles.footerLink, styles.footerLinkPrimary]}>
          + Add
        </Link>
        <Link href="/tasks/new" style={styles.footerLink}>
          + Task
        </Link>
        <Link href="/meds" style={styles.footerLink}>
          Medications
        </Link>
        <Link href="/backlog" style={styles.footerLink}>
          Backlog
        </Link>
        <Link href="/adherence" style={styles.footerLink}>
          How it&apos;s going
        </Link>
        <Link href="/refill" style={styles.footerLink}>
          Supplies
        </Link>
        <Link href="/dose/history" style={styles.footerLink}>
          History
        </Link>
        <Link href="/debug/notifications" style={styles.footerLink}>
          Debug
        </Link>
        <Link href="/debug/stress-seed" style={styles.footerLink}>
          Seed
        </Link>
      </View>

      <View style={styles.accountBar}>
        <UserBadge />
        <SignOutButton />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  accountBar: {
    alignItems: "center",
    borderTopColor: "#eee",
    borderTopWidth: 1,
    flexDirection: "row",
    justifyContent: "space-between",
    paddingBottom: 8,
    paddingHorizontal: 16,
    paddingTop: 4,
  },
  accountName: {
    color: "#666",
    flexShrink: 1,
    fontSize: 13,
  },
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
  dayArrowOff: {
    color: "#ccc",
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
  entryMedication: {
    borderLeftColor: "#0b8043",
  },
  entryNameRow: {
    alignItems: "center",
    flexDirection: "row",
    gap: 8,
  },
  entryTask: {
    borderLeftColor: "#8a6d00",
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
  // No `borderLeftColor` here, deliberately. It used to paint the "next" row's
  // left edge blue, which overrode the Kind colour on exactly the row the eye
  // goes to first — so the one entry a person reads at a glance was the one
  // that could not be told apart at a glance. "Next" now says so with the tint
  // and the badge, which are signals the Kind colour does not use.
  entryNext: {
    backgroundColor: "#f2f8ff",
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
  // Wrapping, since #15 added a seventh link: on a narrow phone the row used to
  // squeeze every label until they ran together rather than moving one down.
  footer: {
    borderTopColor: "#eee",
    borderTopWidth: 1,
    columnGap: 16,
    flexDirection: "row",
    flexWrap: "wrap",
    justifyContent: "center",
    paddingHorizontal: 12,
    paddingVertical: 12,
    rowGap: 8,
  },
  footerLink: {
    color: "#007aff",
    fontSize: 15,
    fontWeight: "500",
  },
  footerLinkPrimary: {
    backgroundColor: "#007aff",
    borderRadius: 6,
    color: "#fff",
    fontWeight: "700",
    overflow: "hidden",
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  kindTag: {
    borderRadius: 4,
    fontSize: 11,
    fontWeight: "700",
    letterSpacing: 0.3,
    overflow: "hidden",
    paddingHorizontal: 5,
    paddingVertical: 1,
  },
  kindTagMed: {
    backgroundColor: "#e6f4ea",
    color: "#0b8043",
  },
  kindTagTask: {
    backgroundColor: "#fdf3d8",
    color: "#8a6d00",
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
