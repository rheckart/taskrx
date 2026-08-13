import { afterAll, beforeAll, describe, expect, it } from "vitest";

import type { InstanceStatus, ReminderKind } from "../domain/enums";
import type { ReminderInstance } from "../domain/reminder";
import { createReminderInstance } from "../domain/reminder";

import type { AdherenceStats } from "./calculator";
import { computeAdherence } from "./calculator";

const MS_PER_HOUR = 3_600_000;
const MS_PER_DAY = MS_PER_HOUR * 24;

/** Local wall-clock helper, matching the pattern in `occurrences.test.ts`. */
function local(
  year: number,
  month: number,
  day: number,
  hour = 0,
  minute = 0
): number {
  return new Date(year, month - 1, day, hour, minute, 0, 0).getTime();
}

/** Mirrors the `Instance(...)` helper in `AdherenceCalculatorTests.cs`. */
function instance(
  scheduledTime: number,
  status: InstanceStatus,
  reminderKind: ReminderKind = "medication"
): ReminderInstance {
  return createReminderInstance({
    id: "instance",
    reminderId: "reminder-1",
    userId: "user-1",
    reminderKind,
    scheduledTime,
    status,
    createdAt: 0,
    updatedAt: 0,
  });
}

/**
 * `stats.daily[index]` accessors that avoid `!`, per the `occurrences.test.ts`
 * pattern (`?? Number.NaN` / `?? ""` instead of a non-null assertion).
 */
function dayDate(stats: AdherenceStats, index: number): string {
  return stats.daily[index]?.date ?? "";
}
function dayTaken(stats: AdherenceStats, index: number): number {
  return stats.daily[index]?.completed ?? Number.NaN;
}
function dayTotal(stats: AdherenceStats, index: number): number {
  return stats.daily[index]?.total ?? Number.NaN;
}
function dayPercent(stats: AdherenceStats, index: number): number {
  return stats.daily[index]?.percent ?? Number.NaN;
}

describe("computeAdherence", () => {
  // Compute_AllTakenInWindow_ReturnsOneHundredPercent
  it("returns 100 percent when everything in the window was taken", () => {
    const from = local(2026, 7, 1);
    const to = from + MS_PER_DAY;
    const instances = [
      instance(from + 8 * MS_PER_HOUR, "completed"),
      instance(from + 14 * MS_PER_HOUR, "completed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.percent).toBe(100);
    expect(stats.completed).toBe(2);
    expect(stats.missed).toBe(0);
    expect(stats.skipped).toBe(0);
    expect(stats.snoozed).toBe(0);
    expect(stats.total).toBe(2);
  });

  // Compute_AllMissed_ReturnsZeroPercent
  it("returns 0 percent when everything in the window was missed", () => {
    const from = local(2026, 7, 1);
    const to = from + MS_PER_DAY;
    const instances = [
      instance(from + 8 * MS_PER_HOUR, "missed"),
      instance(from + 14 * MS_PER_HOUR, "missed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.percent).toBe(0);
    expect(stats.completed).toBe(0);
    expect(stats.missed).toBe(2);
    expect(stats.skipped).toBe(0);
    expect(stats.total).toBe(2);
  });

  // Compute_MixedStatuses_ReturnsFiftyPercent
  it("returns 50 percent for a mix of taken, missed, and skipped", () => {
    const from = local(2026, 7, 1);
    const to = from + MS_PER_DAY;
    const instances = [
      instance(from + 8 * MS_PER_HOUR, "completed"),
      instance(from + 10 * MS_PER_HOUR, "completed"),
      instance(from + 12 * MS_PER_HOUR, "missed"),
      instance(from + 14 * MS_PER_HOUR, "skipped"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.percent).toBe(50);
    expect(stats.completed).toBe(2);
    expect(stats.missed).toBe(1);
    expect(stats.skipped).toBe(1);
    expect(stats.total).toBe(4);
  });

  // Compute_PendingOnly_ReturnsZeroPercentAndZeroTotal
  // Unaffected by the Snoozed fix: Pending stays excluded from both sides,
  // regardless of reminderKind.
  it("returns 0 percent and 0 total when everything is still pending", () => {
    const from = local(2026, 7, 1);
    const to = from + MS_PER_DAY;
    const instances = [
      instance(from + 8 * MS_PER_HOUR, "pending"),
      instance(from + 14 * MS_PER_HOUR, "pending"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.percent).toBe(0);
    expect(stats.completed).toBe(0);
    expect(stats.missed).toBe(0);
    expect(stats.skipped).toBe(0);
    expect(stats.snoozed).toBe(0);
    expect(stats.total).toBe(0);
    // The days are still listed — they are days of the period. What a pending
    // instance must not do is put anything in one.
    expect(stats.daily.every((day) => day.total === 0)).toBe(true);
  });

  // Compute_InstancesOutsideWindow_AreExcluded
  it("excludes instances scheduled outside the inclusive window", () => {
    const from = local(2026, 7, 2);
    const to = from + MS_PER_DAY;
    const instances = [
      instance(from - MS_PER_DAY + 8 * MS_PER_HOUR, "completed"),
      instance(from + 8 * MS_PER_HOUR, "missed"),
      instance(to + MS_PER_DAY + 8 * MS_PER_HOUR, "completed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.percent).toBe(0);
    expect(stats.completed).toBe(0);
    expect(stats.missed).toBe(1);
    expect(stats.skipped).toBe(0);
    expect(stats.total).toBe(1);
  });

  // Compute_DailyGrouping_CountsPerDateCorrectly
  // Instances are given day-2-before-day-1 so the assertion on ascending
  // order is real rather than a trivial reflection of already-sorted input.
  it("groups into local calendar days, ordered chronologically ascending", () => {
    const from = local(2026, 7, 1);
    const to = local(2026, 7, 2, 23, 59);
    const instances = [
      instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, "completed"),
      instance(from + MS_PER_DAY + 21 * MS_PER_HOUR, "skipped"),
      instance(from + 8 * MS_PER_HOUR, "completed"),
      instance(from + 14 * MS_PER_HOUR, "missed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.daily).toHaveLength(2);
    expect(dayDate(stats, 0)).toBe("2026-07-01");
    expect(dayTaken(stats, 0)).toBe(1);
    expect(dayTotal(stats, 0)).toBe(2);
    expect(dayPercent(stats, 0)).toBe(50);
    expect(dayDate(stats, 1)).toBe("2026-07-02");
    expect(dayTaken(stats, 1)).toBe(1);
    expect(dayTotal(stats, 1)).toBe(2);
    expect(dayPercent(stats, 1)).toBe(50);
  });

  // Complements the reversed-input case above: exercises the comparator's
  // other branch, where a day bucket is already ahead of the one before it.
  it("keeps already-ascending day buckets in order", () => {
    const from = local(2026, 7, 1);
    const to = local(2026, 7, 2, 23, 59);
    const instances = [
      instance(from + 8 * MS_PER_HOUR, "completed"),
      instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, "completed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(dayDate(stats, 0)).toBe("2026-07-01");
    expect(dayDate(stats, 1)).toBe("2026-07-02");
  });

  // Compute_EmptyInput_ReturnsZeroPercentAndZeroTotal
  // The C# built `from` from `DateTimeOffset.UtcNow`; replaced with a fixed
  // instant since this is a pure function and the value doesn't matter when
  // there are no instances to filter against it.
  it("returns 0 percent and 0 total for empty input", () => {
    const from = local(2026, 7, 1);
    const to = from + MS_PER_DAY;

    const stats = computeAdherence([], from, to);

    expect(stats.percent).toBe(0);
    expect(stats.completed).toBe(0);
    expect(stats.missed).toBe(0);
    expect(stats.skipped).toBe(0);
    expect(stats.snoozed).toBe(0);
    expect(stats.total).toBe(0);
    expect(stats.daily.every((day) => day.total === 0)).toBe(true);
  });

  describe("the Snoozed fix (divergence from the C#)", () => {
    it("counts a snoozed medication instance in the denominator, not the numerator", () => {
      const from = local(2026, 7, 1);
      const to = from + MS_PER_DAY;
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + 14 * MS_PER_HOUR, "snoozed"), // medication, default kind
      ];

      const stats = computeAdherence(instances, from, to);

      // Fixed behaviour: the snooze counts in total (2) but not in taken (1),
      // giving 50%.
      expect(stats.total).toBe(2);
      expect(stats.completed).toBe(1);
      expect(stats.snoozed).toBe(1);
      expect(stats.percent).toBe(50);

      // The C# `AdherenceCalculator` drops Snoozed from both numerator and
      // denominator, so it would have reported total=1, taken=1 -> 100%.
      // Pin that number here so the inflation this port fixes stays visible.
      const inflatedPercentTheCSharpWouldHaveReported = 100;
      expect(stats.percent).toBeLessThan(
        inflatedPercentTheCSharpWouldHaveReported
      );
    });

    it("excludes a snoozed task instance from both the numerator and denominator", () => {
      const from = local(2026, 7, 1);
      const to = from + MS_PER_DAY;
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + 14 * MS_PER_HOUR, "snoozed", "task"),
      ];

      const stats = computeAdherence(instances, from, to);

      expect(stats.total).toBe(1);
      expect(stats.completed).toBe(1);
      expect(stats.snoozed).toBe(0);
      expect(stats.percent).toBe(100);
    });

    it("gives a mixed day's bucket the same denominator as the overall total", () => {
      const from = local(2026, 7, 1);
      const to = local(2026, 7, 1, 23, 59);
      const instances = [
        instance(from + 6 * MS_PER_HOUR, "completed"),
        instance(from + 8 * MS_PER_HOUR, "snoozed"), // medication: counts in the denominator
        instance(from + 10 * MS_PER_HOUR, "snoozed", "task"), // excluded entirely
        instance(from + 12 * MS_PER_HOUR, "missed"),
      ];

      const stats = computeAdherence(instances, from, to);

      // Overall: taken(1) + medication-snoozed(1) + missed(1) = 3; the
      // task-snoozed instance is excluded entirely, like Pending.
      expect(stats.total).toBe(3);
      expect(stats.completed).toBe(1);
      expect(stats.missed).toBe(1);
      expect(stats.snoozed).toBe(1);

      // The single day bucket must use the identical rule: 3, not 4 (which
      // is what the C#'s `dayTotal = g.Count()` would give by counting the
      // task snooze too) and not 2 (which is what it would be if a snooze
      // were silently dropped from the day bucket the way the C# drops it
      // from the overall total).
      expect(stats.daily).toHaveLength(1);
      expect(dayTotal(stats, 0)).toBe(3);
      expect(dayTaken(stats, 0)).toBe(1);
    });
  });

  describe("Adherence is medication-only", () => {
    /**
     * The rule stated as the person experiences it: a productive week must not
     * disguise a bad medication week. Half the doses were taken, so the figure
     * is 50% — and stays 50% however many tasks were ticked off beside them.
     */
    it("does not move the medication figure when a week of tasks is completed", () => {
      const from = local(2026, 7, 1);
      const to = from + 7 * MS_PER_DAY;

      const doses = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + 20 * MS_PER_HOUR, "missed"),
      ];
      const tasks = Array.from({ length: 7 }, (_, day) =>
        instance(
          from + day * MS_PER_DAY + 10 * MS_PER_HOUR,
          "completed",
          "task"
        )
      );

      const withoutTasks = computeAdherence(doses, from, to);
      const withTasks = computeAdherence([...doses, ...tasks], from, to);

      expect(withoutTasks.percent).toBe(50);
      expect(withTasks).toEqual(withoutTasks);
    });

    it("does not let a missed task count as a missed dose", () => {
      const from = local(2026, 7, 1);
      const to = from + MS_PER_DAY;
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + 14 * MS_PER_HOUR, "missed", "task"),
      ];

      const stats = computeAdherence(instances, from, to);

      expect(stats.total).toBe(1);
      expect(stats.missed).toBe(0);
      expect(stats.percent).toBe(100);
    });

    it("does not let a skipped task count as a skipped dose", () => {
      const from = local(2026, 7, 1);
      const to = from + MS_PER_DAY;
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + 14 * MS_PER_HOUR, "skipped", "task"),
      ];

      const stats = computeAdherence(instances, from, to);

      expect(stats.total).toBe(1);
      expect(stats.skipped).toBe(0);
      expect(stats.percent).toBe(100);
    });

    it("gives a day of nothing but tasks no medication commitments at all", () => {
      const from = local(2026, 7, 1);
      const to = local(2026, 7, 2, 23, 59);
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, "completed", "task"),
      ];

      const stats = computeAdherence(instances, from, to);

      // The 2nd is listed, because it is a day of the period — but a completed
      // task put nothing in it, so it reads as a day with no doses rather than
      // as a day that went well.
      expect(dayDate(stats, 1)).toBe("2026-07-02");
      expect(dayTotal(stats, 1)).toBe(0);
      expect(dayTaken(stats, 1)).toBe(0);
    });
  });

  /**
   * The two figures on the Adherence surface are the headline percentage and the
   * day-by-day list beneath it, and a person reading them can add the second up.
   * The C# could not survive that: `AdherenceCalculator.cs:24`'s
   * `dayTotal = g.Count()` counted Snoozed while the overall total did not, so
   * the days summed to more than the whole.
   *
   * Stated as the invariant rather than as one arithmetic example, because it is
   * the invariant a reader is checking — no combination of statuses and Kinds may
   * make the parts disagree with the sum.
   */
  describe("the daily breakdown and the overall figure agree", () => {
    it("sums the daily buckets back to the overall totals", () => {
      const from = local(2026, 7, 1);
      const to = from + 4 * MS_PER_DAY;
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + 20 * MS_PER_HOUR, "snoozed"),
        instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, "missed"),
        instance(from + MS_PER_DAY + 21 * MS_PER_HOUR, "skipped"),
        instance(from + 2 * MS_PER_DAY + 9 * MS_PER_HOUR, "completed"),
        instance(from + 2 * MS_PER_DAY + 10 * MS_PER_HOUR, "pending"),
        instance(from + 2 * MS_PER_DAY + 11 * MS_PER_HOUR, "completed", "task"),
        instance(from + 3 * MS_PER_DAY + 9 * MS_PER_HOUR, "snoozed", "task"),
      ];

      const stats = computeAdherence(instances, from, to);

      const summed = stats.daily.reduce(
        (running, day) => ({
          completed: running.completed + day.completed,
          total: running.total + day.total,
        }),
        { completed: 0, total: 0 }
      );

      expect(summed.total).toBe(stats.total);
      expect(summed.completed).toBe(stats.completed);
      // And the headline is that sum expressed as a percentage, so a person who
      // averages the days they can see arrives where the app already is.
      expect(stats.percent).toBe((summed.completed / summed.total) * 100);
    });

    it("leaves no day out, so a day of pure failure still lands in the list", () => {
      const from = local(2026, 7, 1);
      const to = local(2026, 7, 2, 23, 59);
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, "missed"),
      ];

      const stats = computeAdherence(instances, from, to);

      expect(stats.daily.map((day) => day.date)).toEqual([
        "2026-07-01",
        "2026-07-02",
      ]);
      expect(dayPercent(stats, 1)).toBe(0);
    });
  });
});

/**
 * The daily breakdown covers the PERIOD, not just the parts of it that have
 * data.
 *
 * A day on which nothing was recorded used to be absent from `daily` entirely,
 * which left the surface rendering it no differently from a day the reader had
 * scrolled past — the list simply skipped from Monday to Wednesday. ADR 0005
 * endorses the underlying reading ("a gap reads as no data rather than as a wall
 * of Missed"), but a gap nobody can see is not a reading, it is an omission.
 *
 * The empty day carries `total: 0`, which is what distinguishes it. `percent` is
 * `0` there only because `0/0` has to be something — a consumer must test
 * `total` and not `percent`, or it will render a day with nothing due as a day on
 * which every dose was missed. Those are opposite facts.
 *
 * This is deliberately NOT "every day a dose was due": the calculator sees
 * Instances and never Reminders, so it cannot tell a day with nothing scheduled
 * from a day whose history the sweep could not reach. Both are honestly "no
 * doses recorded", and neither is invented as a miss.
 */
describe("computeAdherence — the breakdown spans the whole period", () => {
  it("includes a day in the middle of the period on which nothing was recorded", () => {
    const from = local(2026, 7, 1);
    const to = local(2026, 7, 3, 23, 59);
    const instances = [
      instance(local(2026, 7, 1, 8), "completed"),
      // Nothing at all on the 2nd.
      instance(local(2026, 7, 3, 8), "completed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.daily.map((day) => day.date)).toEqual([
      "2026-07-01",
      "2026-07-02",
      "2026-07-03",
    ]);
    expect(dayTotal(stats, 1)).toBe(0);
    expect(dayTaken(stats, 1)).toBe(0);
  });

  it("gives a period with no history at all one row per day, not none", () => {
    const stats = computeAdherence(
      [],
      local(2026, 7, 1),
      local(2026, 7, 7, 23, 59)
    );

    expect(stats.daily).toHaveLength(7);
    expect(stats.daily.every((day) => day.total === 0)).toBe(true);
    // The overall figure is still the "no data" one, not a failing one.
    expect(stats.total).toBe(0);
    expect(stats.percent).toBe(0);
  });

  it("counts an empty day as no commitments rather than as missed ones", () => {
    const from = local(2026, 7, 1);
    const to = local(2026, 7, 2, 23, 59);
    const instances = [instance(local(2026, 7, 1, 8), "completed")];

    const stats = computeAdherence(instances, from, to);

    // The empty day must not drag the overall figure down: nothing was due, so
    // nothing was missed, and the week was in fact perfect.
    expect(stats.percent).toBe(100);
    expect(stats.total).toBe(1);
    expect(dayTotal(stats, 1)).toBe(0);
  });

  it("still sums the daily buckets back to the overall totals", () => {
    const from = local(2026, 7, 1);
    const to = local(2026, 7, 5, 23, 59);
    const instances = [
      instance(local(2026, 7, 1, 8), "completed"),
      instance(local(2026, 7, 4, 8), "missed"),
    ];

    const stats = computeAdherence(instances, from, to);

    const summedTotal = stats.daily.reduce((run, day) => run + day.total, 0);
    expect(stats.daily).toHaveLength(5);
    expect(summedTotal).toBe(stats.total);
  });

  it("orders the days chronologically whatever order the instances arrive in", () => {
    const from = local(2026, 7, 1);
    const to = local(2026, 7, 3, 23, 59);
    const instances = [
      instance(local(2026, 7, 3, 8), "completed"),
      instance(local(2026, 7, 1, 8), "completed"),
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.daily.map((day) => day.date)).toEqual([
      "2026-07-01",
      "2026-07-02",
      "2026-07-03",
    ]);
  });

  it("walks across a month boundary", () => {
    const stats = computeAdherence(
      [],
      local(2026, 1, 30),
      local(2026, 2, 2, 23, 59)
    );

    expect(stats.daily.map((day) => day.date)).toEqual([
      "2026-01-30",
      "2026-01-31",
      "2026-02-01",
      "2026-02-02",
    ]);
  });

  it("emits nothing for a window that ends before it starts", () => {
    const stats = computeAdherence([], local(2026, 7, 3), local(2026, 7, 1));

    expect(stats.daily).toEqual([]);
  });
});

/**
 * Days are local calendar days on both sides of a clock change.
 *
 * The two implementations that fail here are the ones a reader reaches for
 * first: `toISOString().slice(0, 10)` groups by the UTC date, which puts a
 * 21:00 dose on tomorrow for every North American offset; and
 * `Math.floor(ms / 86_400_000)` groups by UTC day-number, which does the same
 * and additionally shifts by an hour when the offset changes, so one calendar
 * day's doses split across two buckets. Either one makes the day-by-day list
 * disagree with the day the person remembers.
 */
describe("computeAdherence — daylight saving", () => {
  const previousTz = process.env.TZ;

  beforeAll(() => {
    process.env.TZ = "America/New_York";
  });

  afterAll(() => {
    process.env.TZ = previousTz;
  });

  it("keeps a spring-forward day's doses on that one day", () => {
    // 2027-03-14 is the US spring-forward date: a 23-hour local day.
    const from = local(2027, 3, 13);
    const to = local(2027, 3, 15, 23, 59);
    const instances = [
      instance(local(2027, 3, 14, 9), "completed"),
      instance(local(2027, 3, 14, 21), "missed"),
    ];

    const stats = computeAdherence(instances, from, to);

    // Three rows for three calendar days: the 23-hour day is one day, not two
    // and not a fraction of one, which is also what pins the day-walk itself as
    // DST-safe — a fixed 24-hour step would slip and emit a duplicate key.
    expect(stats.daily).toHaveLength(3);
    expect(dayDate(stats, 1)).toBe("2027-03-14");
    expect(dayTotal(stats, 1)).toBe(2);
  });

  it("keeps a fall-back day's doses on that one day", () => {
    // 2027-11-07 is the US fall-back date: a 25-hour local day, and the one
    // where 01:30 happens twice.
    const from = local(2027, 11, 6);
    const to = local(2027, 11, 8, 23, 59);
    const instances = [
      instance(local(2027, 11, 7, 9), "completed"),
      instance(local(2027, 11, 7, 21), "completed"),
    ];

    const stats = computeAdherence(instances, from, to);

    // And three for the 25-hour day, where a fixed step slips the other way.
    expect(stats.daily).toHaveLength(3);
    expect(dayDate(stats, 1)).toBe("2027-11-07");
    expect(dayTotal(stats, 1)).toBe(2);
  });

  it("separates a late dose from the next morning's across a transition", () => {
    const from = local(2027, 3, 13);
    const to = local(2027, 3, 15, 23, 59);
    const instances = [
      instance(local(2027, 3, 13, 23), "completed"), // late on the eve
      instance(local(2027, 3, 14, 9), "missed"), // the morning after
    ];

    const stats = computeAdherence(instances, from, to);

    expect(stats.daily.map((day) => day.date)).toEqual([
      "2027-03-13",
      "2027-03-14",
      "2027-03-15",
    ]);
    expect(dayTotal(stats, 0)).toBe(1);
    expect(dayTotal(stats, 1)).toBe(1);
  });
});
