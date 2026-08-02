/**
 * The adherence calculator, ported from `AdherenceCalculator.Compute` in
 * `TaskRx.Core/AdherenceCalculator.cs` on the `archive/dotnet-maui` branch.
 *
 * ## Divergence from the C#: the Snoozed denominator
 *
 * `AdherenceCalculator.cs:7-15` filters out `Pending`, then counts only
 * `Taken`/`Missed`/`Skipped` toward `total`. A `Snoozed` instance therefore
 * falls into neither the numerator nor the denominator and vanishes —
 * snoozing a dose silently inflates the adherence score. `AdherenceCalculator.cs:24`
 * compounds this: the daily grouping's `dayTotal = g.Count()` DOES include
 * `Snoozed`, so the overall percentage and the daily percentages end up
 * computed against different denominators.
 *
 * The README states the intent directly: "a snoozed medication affects the
 * streak; a snoozed task just reschedules." This port implements that
 * explicitly, using `reminderKind` to split the treatment:
 *
 * - `snoozed` + `medication` -> counts in the DENOMINATOR only. It lowers the
 *   percentage the same way a miss would, without being reported as one.
 * - `snoozed` + `task` -> excluded entirely, from both numerator and
 *   denominator — same treatment as `pending`. Snoozing a task just
 *   reschedules it.
 * - `pending` -> excluded entirely regardless of kind, as in the C#.
 *
 * The daily buckets use this same denominator rule as the overall total, so
 * the two figures can never disagree the way the C#'s did. `snoozed` is
 * surfaced as its own field on `AdherenceStats` — counting only the
 * medication snoozes that actually affected `total` — so the treatment is
 * visible in the output rather than an implicit side effect of the
 * completed/missed/skipped math.
 *
 * Local-calendar-date grouping follows the pattern established in
 * `../schedule/occurrences.ts`: built from `Date.getFullYear()` /
 * `getMonth()` / `getDate()`, not `toISOString()` (UTC) and not epoch-ms
 * division (UTC, and DST-unsafe).
 *
 * Pure function: no DB calls, no `Date.now()`, no globals.
 */

import type { ReminderInstance } from "../domain/reminder";

/** `TaskRx.Core.DayAdherence`. `date` is a `DateOnly` day key, `"YYYY-MM-DD"`. */
export interface DayAdherence {
  readonly date: string;
  readonly completed: number;
  readonly total: number;
  readonly percent: number;
}

/**
 * `TaskRx.Core.AdherenceStats`, plus `snoozed` — see the file header for why
 * that field exists here but not on the C# record.
 *
 * `completed` is what the C# record calls `Taken`. Renamed with the status it
 * counts (ADR 0001): the glossary lists "Taken" under _Avoid_ for the Completed
 * concept, and Adherence has no carve-out from that. Adherence being a
 * medication-only concept makes the medication word defensible, not correct.
 */
export interface AdherenceStats {
  readonly percent: number;
  readonly completed: number;
  readonly missed: number;
  readonly skipped: number;
  readonly snoozed: number;
  readonly total: number;
  readonly daily: readonly DayAdherence[];
}

/**
 * Whether `instance` counts toward the denominator, per the Snoozed
 * treatment documented above. `AdherenceCalculator.cs:8`'s
 * `i.Status != InstanceStatus.Pending`, refined to also drop a snoozed task.
 */
function countsTowardTotal(instance: ReminderInstance): boolean {
  if (instance.status === "pending") return false;
  if (instance.status === "snoozed" && instance.reminderKind === "task") {
    return false;
  }
  return true;
}

/**
 * The local calendar date `ms` falls on, as `"YYYY-MM-DD"`. The TS analogue
 * of `DateOnly.FromDateTime(TimeZoneInfo.ConvertTime(i.ScheduledTime, zone).DateTime)`.
 */
function localDateKey(ms: number): string {
  const date = new Date(ms);
  const year = String(date.getFullYear()).padStart(4, "0");
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

/** `completed / total * 100`, or `0` when `total` is `0` — `AdherenceCalculator.cs:15,25`. */
function percentOf(completed: number, total: number): number {
  return total > 0 ? (completed / total) * 100 : 0;
}

interface DayBucket {
  completed: number;
  total: number;
}

/**
 * Adherence over `[fromMs, toMs]` (both ends inclusive, matching the C#'s
 * `scheduledTime >= fromUtc && scheduledTime <= toUtc`), grouped into local
 * calendar days ordered chronologically ascending.
 */
export function computeAdherence(
  instances: readonly ReminderInstance[],
  fromMs: number,
  toMs: number
): AdherenceStats {
  const counted = instances.filter(
    (instance) =>
      instance.scheduledTime >= fromMs &&
      instance.scheduledTime <= toMs &&
      countsTowardTotal(instance)
  );

  const completed = counted.filter(
    (instance) => instance.status === "completed"
  ).length;
  const missed = counted.filter(
    (instance) => instance.status === "missed"
  ).length;
  const skipped = counted.filter(
    (instance) => instance.status === "skipped"
  ).length;
  const snoozed = counted.filter(
    (instance) => instance.status === "snoozed"
  ).length;
  const total = counted.length;
  const percent = percentOf(completed, total);

  const buckets = new Map<string, DayBucket>();
  for (const instance of counted) {
    const key = localDateKey(instance.scheduledTime);
    const bucket = buckets.get(key) ?? { completed: 0, total: 0 };
    bucket.total += 1;
    if (instance.status === "completed") bucket.completed += 1;
    buckets.set(key, bucket);
  }

  // Map keys are distinct "YYYY-MM-DD" strings, so `a === b` never happens —
  // a two-way comparison, not three, keeps every branch reachable.
  const daily = [...buckets.entries()]
    .sort(([a], [b]) => (a < b ? -1 : 1))
    .map(
      ([date, bucket]): DayAdherence => ({
        date,
        completed: bucket.completed,
        total: bucket.total,
        percent: percentOf(bucket.completed, bucket.total),
      })
    );

  return { percent, completed, missed, skipped, snoozed, total, daily };
}
