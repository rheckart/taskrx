import { useUser } from "@clerk/clerk-expo";
import type { AdherenceStats, DayAdherence } from "@taskrx/shared";
import { ADHERENCE_PERIOD_DAYS } from "@taskrx/shared";
import { useFocusEffect } from "expo-router";
import { useCallback, useState } from "react";
import type { ReactElement } from "react";
import {
  ActivityIndicator,
  FlatList,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from "react-native";

import { readAdherence } from "../../src/adherence/read";
import { SkeletonList } from "../../src/components/Skeleton";
import { formatDayKey } from "../../src/format/datetime";
import { formatPercent } from "../../src/format/percent";
import { useUnifiedSync } from "../../src/sync/pull";

/**
 * The Adherence surface — "am I actually taking my medication?"
 *
 * Every rule lives somewhere else. `computeAdherence` in `@taskrx/shared` decides
 * what the figure is, `adherencePeriod` decides what week it covers, and
 * `src/adherence/read.ts` decides that the missed sweep runs before either of
 * them is consulted. This file picks none of that: it asks for a reading and
 * renders it. If a judgement about what counts ever appears below, it is in the
 * wrong file.
 *
 * ## Why this screen exists at all
 *
 * The calculator was ported and fully tested months ago and had never been
 * called by anything — it was referenced only in a comment on `dose/history.tsx`.
 * It stayed uncalled deliberately: until the missed sweep landed in #11, an
 * unanswered dose left no row at all, so the figure counted only the doses a
 * person had answered and someone who took 1 of 30 read as 100%. Shipping this
 * screen before that would not have been imprecise, it would have presented a
 * falsehood as a medical fact.
 *
 * ## What it shows, and why the snooze is on its own line
 *
 * A snoozed dose lowers the figure the way a miss would — it is in the
 * denominator and not the numerator — because a dose deferred is a dose not yet
 * taken. But it is not reported AS a miss, because the person made a decision
 * rather than forgetting, and a surface that called it a failure would be
 * telling them something they know to be untrue about their own week. So it gets
 * its own count and its own sentence saying what it did to the number. Folding
 * it into `missed` would have been one fewer row and a small lie; leaving it out
 * of the summary entirely would leave the arithmetic not adding up, which is
 * worse on this screen than on any other.
 *
 * Tasks are absent, and not by omission here: `computeAdherence` excludes them,
 * so a productive week cannot disguise a bad medication one.
 */

/** The four stat tiles under the headline, in the order they are read. */
interface Stat {
  readonly label: string;
  readonly value: number;
  readonly color: string;
}

function statsRow(stats: AdherenceStats): readonly Stat[] {
  return [
    // "Taken", not "Completed" — ADR 0001 made the STORED vocabulary
    // Kind-neutral and kept the medication word in the UI, and this screen is
    // medication-only by definition.
    { label: "Taken", value: stats.completed, color: "#137333" },
    { label: "Missed", value: stats.missed, color: "#c00" },
    { label: "Skipped", value: stats.skipped, color: "#8a6d00" },
    { label: "Snoozed", value: stats.snoozed, color: "#5b3fa8" },
  ];
}

function periodLabel(): string {
  return `Last ${String(ADHERENCE_PERIOD_DAYS)} days`;
}

function Headline({ stats }: { readonly stats: AdherenceStats }): ReactElement {
  return (
    <View style={styles.headline}>
      <Text style={styles.period}>{periodLabel()}</Text>
      <Text
        style={styles.percent}
        accessibilityLabel={`${formatPercent(stats.percent)} of doses taken over the last ${String(
          ADHERENCE_PERIOD_DAYS
        )} days`}
      >
        {formatPercent(stats.percent)}
      </Text>
      <Text style={styles.fraction}>
        {String(stats.completed)} of {String(stats.total)}{" "}
        {stats.total === 1 ? "dose" : "doses"}
      </Text>
    </View>
  );
}

function StatsRow({ stats }: { readonly stats: AdherenceStats }): ReactElement {
  return (
    <View style={styles.stats}>
      {statsRow(stats).map((stat) => (
        <View key={stat.label} style={styles.stat}>
          <Text style={[styles.statValue, { color: stat.color }]}>
            {String(stat.value)}
          </Text>
          <Text style={styles.statLabel}>{stat.label}</Text>
        </View>
      ))}
    </View>
  );
}

/**
 * Said out loud only when there is a snooze to explain. A standing footnote
 * about how snoozes are counted, on a week containing none, is noise about a
 * thing that did not happen.
 */
function SnoozeNote({
  stats,
}: {
  readonly stats: AdherenceStats;
}): ReactElement | null {
  if (stats.snoozed === 0) return null;

  return (
    <Text style={styles.note}>
      {stats.snoozed === 1
        ? "1 snoozed dose counts against the figure — a dose put off is a dose not yet taken."
        : `${String(stats.snoozed)} snoozed doses count against the figure — a dose put off is a dose not yet taken.`}
    </Text>
  );
}

/**
 * A day of the period on which nothing was recorded.
 *
 * It has to be told apart from a 0% day, and `percent` cannot do it — an empty
 * day's percent is `0` because `0/0` has to be some number, and rendering that
 * as "0%" would report a day with nothing due as a day on which every dose was
 * missed. Those are opposite facts, and on this surface the wrong one is an
 * accusation. `total` is the field that distinguishes them, which is what
 * `DayAdherence` says to read.
 *
 * The row still appears, rather than the day being skipped: ADR 0005 records
 * that a gap in history "reads as no data rather than as a wall of Missed", and
 * a gap the list silently steps over is not a reading anybody can have — it looks
 * identical to a day scrolled past.
 */
function EmptyDayRow({ day }: { readonly day: DayAdherence }): ReactElement {
  return (
    <View
      style={styles.dayRow}
      accessible
      accessibilityLabel={`${formatDayKey(day.date)}: no doses recorded`}
    >
      <Text style={[styles.dayName, styles.dayNameEmpty]}>
        {formatDayKey(day.date)}
      </Text>
      <View style={styles.dayBarTrack} />
      <Text style={styles.dayEmpty}>No doses</Text>
    </View>
  );
}

function DayRow({ day }: { readonly day: DayAdherence }): ReactElement {
  if (day.total === 0) return <EmptyDayRow day={day} />;

  return (
    <View
      style={styles.dayRow}
      // `accessible` as well as the label, and the label is inert without it:
      // a plain View is not an accessibility element, so a screen reader would
      // walk the four children and read "Tue, Aug 4", "67%", "2/3" as three
      // unrelated fragments with the bar in between. Grouped, the row is one
      // sentence — which is how the row is read by eye too.
      accessible
      accessibilityLabel={`${formatDayKey(day.date)}: ${formatPercent(
        day.percent
      )}, ${String(day.completed)} of ${String(day.total)} taken`}
    >
      <Text style={styles.dayName}>{formatDayKey(day.date)}</Text>
      {/*
        A bar as well as the two numbers. A week of percentages is a list a
        person has to read one line at a time to find the bad day; the same week
        as bars is one glance. It is not the only signal — the figures beside it
        say the same thing for anyone the bar does not reach.
      */}
      <View style={styles.dayBarTrack}>
        {/*
          Two flex weights rather than a percentage width string: the ratio is
          the thing being drawn, and expressing it as one keeps the arithmetic
          in the layout engine instead of in a rounded string.
        */}
        <View style={[styles.dayBarFill, { flex: day.percent }]} />
        <View style={{ flex: 100 - day.percent }} />
      </View>
      <Text style={styles.dayPercent}>{formatPercent(day.percent)}</Text>
      <Text style={styles.dayCount}>
        {String(day.completed)}/{String(day.total)}
      </Text>
    </View>
  );
}

export default function AdherenceScreen(): ReactElement {
  const { isLoaded, isSignedIn, user } = useUser();
  const userId = user?.id;

  const [stats, setStats] = useState<AdherenceStats | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [refreshing, setRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  // Both halves: the figure is computed from instances, and the sweep that
  // mints the missing ones needs their parent reminders to know what was due.
  useUnifiedSync(userId);

  const load = useCallback(async (): Promise<void> => {
    if (userId === undefined) {
      setStats(null);
      setLoading(false);
      return;
    }

    try {
      const reading = await readAdherence(userId);
      setStats(reading.stats);
      setError(null);
    } catch (err: unknown) {
      // Surfaced, unlike the timeline's sweep failure. There the day is correct
      // with or without the sweep; here a figure computed over a sweep that did
      // not finish is the flattering one, and a wrong number that looks like an
      // answer is worse than no number. See `src/adherence/read.ts`.
      setError(err instanceof Error ? err.message : "Unknown error");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [userId]);

  /**
   * On focus, not on mount. Answering a dose leaves this screen and comes back,
   * and the figure it came back to would otherwise still be the one from before
   * the dose was recorded.
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
        <Text>Sign in to see your adherence.</Text>
      </View>
    );
  }

  if (loading) {
    return <SkeletonList rows={6} linesPerRow={2} />;
  }

  if (error !== null) {
    return (
      <View style={styles.center}>
        <Text style={styles.errorText}>Failed to load: {error}</Text>
        <Text style={styles.emptyBody}>Pull down to try again.</Text>
      </View>
    );
  }

  if (stats === null || stats.total === 0) {
    return (
      <FlatList
        data={[]}
        renderItem={() => null}
        ListEmptyComponent={
          <View style={styles.center}>
            <Text style={styles.emptyTitle}>Nothing to report yet</Text>
            {/*
              Said plainly rather than shown as "0%", which is the same shape as
              a week in which every dose was missed. A person opening this
              screen on their first day has not failed at anything.
            */}
            <Text style={styles.emptyBody}>
              Once you have doses due, the proportion you took over the last{" "}
              {String(ADHERENCE_PERIOD_DAYS)} days appears here.
            </Text>
          </View>
        }
        contentContainerStyle={styles.emptyContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
      />
    );
  }

  return (
    <FlatList
      data={stats.daily}
      keyExtractor={(day) => day.date}
      renderItem={({ item }) => <DayRow day={item} />}
      ListHeaderComponent={
        <View>
          <Headline stats={stats} />
          <StatsRow stats={stats} />
          <SnoozeNote stats={stats} />
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>Day by day</Text>
          </View>
        </View>
      }
      contentContainerStyle={styles.listContent}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
      }
    />
  );
}

const styles = StyleSheet.create({
  center: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    padding: 24,
  },
  dayBarFill: {
    backgroundColor: "#0b8043",
    borderRadius: 4,
    height: "100%",
  },
  dayBarTrack: {
    backgroundColor: "#eee",
    borderRadius: 4,
    flex: 1,
    flexDirection: "row",
    height: 8,
    marginHorizontal: 12,
    overflow: "hidden",
  },
  dayCount: {
    color: "#666",
    fontSize: 13,
    textAlign: "right",
    width: 44,
  },
  dayEmpty: {
    color: "#999",
    fontSize: 13,
    fontStyle: "italic",
    textAlign: "right",
    width: 92,
  },
  dayName: {
    fontSize: 14,
    fontWeight: "600",
    width: 92,
  },
  // Muted, so a week's worth of them recedes and the days that carry a figure
  // are what the eye lands on.
  dayNameEmpty: {
    color: "#999",
    fontWeight: "500",
  },
  dayPercent: {
    fontSize: 14,
    fontWeight: "600",
    textAlign: "right",
    width: 48,
  },
  dayRow: {
    alignItems: "center",
    borderBottomColor: "#eee",
    borderBottomWidth: 1,
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  emptyBody: {
    color: "#666",
    marginTop: 8,
    textAlign: "center",
  },
  emptyContent: {
    flexGrow: 1,
  },
  emptyTitle: {
    fontSize: 18,
    fontWeight: "600",
  },
  errorText: {
    color: "#c00",
    textAlign: "center",
  },
  fraction: {
    color: "#666",
    fontSize: 15,
    marginTop: 4,
  },
  headline: {
    alignItems: "center",
    paddingBottom: 16,
    paddingTop: 24,
  },
  listContent: {
    paddingBottom: 24,
  },
  note: {
    color: "#5b3fa8",
    fontSize: 13,
    paddingBottom: 4,
    paddingHorizontal: 16,
    paddingTop: 12,
  },
  percent: {
    fontSize: 56,
    fontWeight: "700",
    letterSpacing: -1,
  },
  period: {
    color: "#666",
    fontSize: 13,
    fontWeight: "600",
    letterSpacing: 0.5,
    textTransform: "uppercase",
  },
  sectionHeader: {
    backgroundColor: "#f5f5f5",
    marginTop: 12,
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  sectionTitle: {
    color: "#444",
    fontSize: 14,
    fontWeight: "700",
    textTransform: "uppercase",
  },
  stat: {
    alignItems: "center",
    flex: 1,
  },
  statLabel: {
    color: "#666",
    fontSize: 12,
    marginTop: 2,
  },
  statValue: {
    fontSize: 20,
    fontWeight: "700",
  },
  stats: {
    borderTopColor: "#eee",
    borderTopWidth: 1,
    flexDirection: "row",
    paddingTop: 16,
  },
});
