/**
 * The adherence calculator, ported from `AdherenceCalculator.Compute` in
 * `TaskRx.Core/AdherenceCalculator.cs` on the `archive/dotnet-maui` branch.
 *
 * ## Adherence is medication-only
 *
 * A task never contributes, in any status. CONTEXT.md defines Adherence as "the
 * proportion of a person's medication commitments they actually met" and says
 * so outright: "a medication concept only — tasks never contribute to it." The
 * spec's user story 37 gives the reason from the person's side — "I want my
 * tasks excluded from that figure, so that a productive week cannot disguise a
 * bad medication week" — and a completed task counted in both halves of
 * `completed / total` does exactly that disguising, pulling the figure toward
 * 100% for a week in which no dose was taken at all.
 *
 * This was latent until the fused timeline: `reminder_kind` has been on every
 * Instance since the unified tables landed, but no task could be created, so
 * every row this saw was a medication one.
 *
 * ## Divergence from the C#: the Snoozed denominator
 *
 * Kind-neutral in the C# because the C# has one Kind. What follows is now the
 * medication half of the rule; the task half is "excluded", above.
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

/**
 * `TaskRx.Core.DayAdherence`. `date` is a `DateOnly` day key, `"YYYY-MM-DD"`.
 *
 * Every local calendar day in the window gets one of these, including days on
 * which nothing was recorded — see `computeAdherence`. Such a day carries
 * `total: 0`, and **`total` is the field that identifies it**. Its `percent` is
 * `0` only because `0/0` has to be some number; read as a percentage it would
 * say every dose that day was missed, which is the opposite of what an empty day
 * means. Test `total`, never `percent`.
 */
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
 * Whether `instance` counts toward the denominator, per the two rules
 * documented above. `AdherenceCalculator.cs:8`'s
 * `i.Status != InstanceStatus.Pending`, refined by Kind.
 *
 * The Kind test comes first and covers every status, so a completed task cannot
 * inflate the figure and a missed one cannot deflate it. That matters for
 * `missed` in particular: nobody records a missed task deliberately — the sweep
 * mints those on its own for any Occurrence that ran out of time, so without
 * this a forgotten task would read as a medication failure.
 */
function countsTowardTotal(instance: ReminderInstance): boolean {
  if (instance.reminderKind === "task") return false;
  if (instance.status === "pending") return false;
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

/**
 * Every local calendar day the window touches, from the day holding `fromMs`
 * through the day holding `toMs`, ascending. Empty when the window is inverted.
 *
 * Stepped with `setDate(getDate() + 1)` rather than by adding 86_400_000, for
 * the reason `../schedule/occurrences.ts` gives: a fixed 24-hour step walks off
 * midnight at a daylight-saving transition and then emits either a duplicate day
 * key or a missing one for the rest of the period. The calendar increment lands
 * on the next local midnight whatever the offset did.
 *
 * The result is already ascending, which is why nothing below sorts: the days
 * are enumerated in order rather than discovered from the data and put back in
 * order afterwards.
 */
function eachLocalDayKey(fromMs: number, toMs: number): readonly string[] {
  const start = new Date(fromMs);
  const cursor = new Date(
    start.getFullYear(),
    start.getMonth(),
    start.getDate(),
    0,
    0,
    0,
    0
  );

  const keys: string[] = [];
  while (cursor.getTime() <= toMs) {
    keys.push(localDateKey(cursor.getTime()));
    cursor.setDate(cursor.getDate() + 1);
  }
  return keys;
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

  // Driven by the window's days rather than by the days the data happens to
  // mention, so a day nobody recorded anything on is a row with `total: 0`
  // instead of a hole the reader cannot see. Every counted instance falls inside
  // the window, so its day key is always one of these — no bucket is orphaned.
  const daily = eachLocalDayKey(fromMs, toMs).map((date): DayAdherence => {
    const bucket = buckets.get(date) ?? { completed: 0, total: 0 };
    return {
      date,
      completed: bucket.completed,
      total: bucket.total,
      percent: percentOf(bucket.completed, bucket.total),
    };
  });

  return { percent, completed, missed, skipped, snoozed, total, daily };
}
