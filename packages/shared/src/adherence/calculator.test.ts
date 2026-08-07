import { describe, expect, it } from "vitest";

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
    expect(stats.daily).toEqual([]);
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
    const to = from + 2 * MS_PER_DAY;
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
    const to = from + 2 * MS_PER_DAY;
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
    expect(stats.daily).toEqual([]);
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
      const to = from + MS_PER_DAY;
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

    it("leaves a day with only tasks out of the daily breakdown", () => {
      const from = local(2026, 7, 1);
      const to = from + 2 * MS_PER_DAY;
      const instances = [
        instance(from + 8 * MS_PER_HOUR, "completed"),
        instance(from + MS_PER_DAY + 9 * MS_PER_HOUR, "completed", "task"),
      ];

      const stats = computeAdherence(instances, from, to);

      expect(stats.daily).toHaveLength(1);
      expect(dayDate(stats, 0)).toBe("2026-07-01");
    });
  });
});
