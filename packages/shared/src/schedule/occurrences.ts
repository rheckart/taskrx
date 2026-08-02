/**
 * The reminder schedule engine, ported from `ScheduleEngine.NextOccurrences`
 * in `TaskRx.Core/ScheduleEngine.cs` on the `archive/dotnet-maui` branch.
 *
 * It works on the unified `Reminder`, so `task` reminders schedule too, and it
 * handles `weekly`. A medication-only predecessor, `engine.ts`, had no shape
 * for either; it was deleted with the legacy generation, and this is now the
 * only schedule engine.
 *
 * Two behaviours differed from that predecessor, deliberately, and are worth
 * recording because they are the ones a reimplementation would get wrong:
 *
 * - **The `from` boundary.** An occurrence landing exactly on `from` is
 *   excluded for every variant, matching `while (next <= fromUtc)` and
 *   `if (utc <= fromUtc) continue;` in the C#. The POC engine included it.
 *   `occurrences.test.ts` still pins this side: the boundary is exactly where
 *   an off-by-one produces a duplicate alarm as a dose comes due.
 * - **Calendar arithmetic.** Day-based schedules step local calendar days, not
 *   fixed 86_400_000 ms hops, which is what `DateOnly.FromDateTime(...)
 *   .AddDays(n)` plus `zone.GetUtcOffset(local)` does on the C# side. Across a
 *   daylight-saving transition a 09:00 dose stays at 09:00 instead of drifting
 *   an hour — the gap between doses is what changes, not the wall-clock time
 *   the user chose.
 *
 * Pure functions: no DB calls, no `Date.now()`, no globals.
 */

import type { Reminder } from "../domain/reminder";
import type {
  DailyTimes,
  IntervalTimes,
  Weekday,
  WeeklyTimes,
} from "../domain/schedule-times";
import { assertNever } from "../domain/schedule-times";

import type { ScheduledOccurrence } from "./types";

const MS_PER_HOUR = 3_600_000;

/** `for (int dayOffset = 0; ...; dayOffset < 60; ...)` in `NextDailyOccurrences`. */
const DAILY_HORIZON_DAYS = 60;

/** Eight weeks, matching the `dayOffset < 56` walk in `NextWeeklyOccurrences`. */
const WEEKLY_HORIZON_DAYS = 56;

/** The C# `count` default on `NextOccurrences`. */
const DEFAULT_COUNT = 64;

/**
 * The fields an occurrence is built from. The C# takes a whole `Reminder`, but
 * only reads these four, and narrowing the input keeps the engine callable from
 * a query projection.
 */
export type OccurrenceSource = Pick<
  Reminder,
  "id" | "name" | "dosage" | "schedule"
>;

interface TimeOfDay {
  readonly hour: number;
  readonly minute: number;
}

/**
 * Parses `"HH:MM"` as a local wall-clock time.
 *
 * C# stores these as `TimeOnly`, which cannot hold an invalid time; the domain
 * type is a string, so malformed entries are dropped rather than trusted.
 */
function parseTimeOfDay(time: string): TimeOfDay | undefined {
  const match = /^([0-9]{1,2}):([0-9]{2})$/.exec(time);
  if (match === null) return undefined;

  const [, hourStr, minuteStr] = match;
  /* v8 ignore next */
  if (hourStr === undefined || minuteStr === undefined) return undefined;

  const hour = Number.parseInt(hourStr, 10);
  const minute = Number.parseInt(minuteStr, 10);
  if (hour > 23 || minute > 59) return undefined;

  return { hour, minute };
}

/** The parseable times, chronologically — the C#'s `Times.OrderBy(x => x)`. */
function sortedTimesOfDay(times: readonly string[]): readonly TimeOfDay[] {
  return times
    .map(parseTimeOfDay)
    .filter((time): time is TimeOfDay => time !== undefined)
    .sort((a, b) => a.hour - b.hour || a.minute - b.minute);
}

/**
 * The instant of `time` on the local calendar day `dayOffset` days after the
 * day containing `reference`.
 *
 * Built from local calendar fields so the local timezone's offset for that
 * specific day is applied. Day-of-month overflow normalizes, as `AddDays` does.
 */
function localFireAt(
  reference: Date,
  dayOffset: number,
  time: TimeOfDay
): number {
  return new Date(
    reference.getFullYear(),
    reference.getMonth(),
    reference.getDate() + dayOffset,
    time.hour,
    time.minute,
    0,
    0
  ).getTime();
}

/** The weekday of the local calendar day `dayOffset` days after `reference`. */
function localWeekday(reference: Date, dayOffset: number): Weekday {
  const day = new Date(
    reference.getFullYear(),
    reference.getMonth(),
    reference.getDate() + dayOffset
  );
  return day.getDay() as Weekday;
}

/**
 * `$"{reminderId}:{fireAtUtc.ToUnixTimeMilliseconds()}"`.
 *
 * Stable for a given reminder and fire time, which is what lets the notification
 * scheduler re-run without duplicating alarms.
 */
function buildOccurrenceId(reminderId: string, fireAt: number): string {
  return `${reminderId}:${String(fireAt)}`;
}

function occurrenceAt(
  source: OccurrenceSource,
  fireAt: number
): ScheduledOccurrence {
  return {
    occurrenceId: buildOccurrenceId(source.id, fireAt),
    reminderId: source.id,
    name: source.name,
    dosage: source.dosage,
    fireAt,
  };
}

function dailyOccurrences(
  source: OccurrenceSource,
  schedule: DailyTimes,
  fromMs: number,
  count: number
): readonly ScheduledOccurrence[] {
  const times = sortedTimesOfDay(schedule.times);
  if (times.length === 0) return [];

  const reference = new Date(fromMs);
  const results: ScheduledOccurrence[] = [];

  for (
    let dayOffset = 0;
    results.length < count && dayOffset < DAILY_HORIZON_DAYS;
    dayOffset++
  ) {
    for (const time of times) {
      const fireAt = localFireAt(reference, dayOffset, time);
      if (fireAt <= fromMs) continue;

      results.push(occurrenceAt(source, fireAt));
      if (results.length >= count) break;
    }
  }

  return results;
}

/**
 * Interval schedules are absolute: they recur every N hours from `startAt`
 * regardless of the calendar, so no local-day walk and no horizon cap. The C#
 * fast-forwards one interval at a time; the arithmetic here lands on the same
 * slot without looping over a start instant far in the past.
 */
function intervalOccurrences(
  source: OccurrenceSource,
  schedule: IntervalTimes,
  fromMs: number,
  count: number
): readonly ScheduledOccurrence[] {
  if (schedule.hours <= 0) return [];

  const intervalMs = schedule.hours * MS_PER_HOUR;
  let next = schedule.startAt;
  if (next <= fromMs) {
    const steps = Math.floor((fromMs - schedule.startAt) / intervalMs) + 1;
    next = schedule.startAt + steps * intervalMs;
  }

  const results: ScheduledOccurrence[] = [];
  for (let i = 0; i < count; i++, next += intervalMs) {
    results.push(occurrenceAt(source, next));
  }

  return results;
}

function weeklyOccurrences(
  source: OccurrenceSource,
  schedule: WeeklyTimes,
  fromMs: number,
  count: number
): readonly ScheduledOccurrence[] {
  const times = sortedTimesOfDay(schedule.times);
  if (schedule.daysOfWeek.length === 0 || times.length === 0) return [];

  const reference = new Date(fromMs);
  const results: ScheduledOccurrence[] = [];

  for (
    let dayOffset = 0;
    results.length < count && dayOffset < WEEKLY_HORIZON_DAYS;
    dayOffset++
  ) {
    if (!schedule.daysOfWeek.includes(localWeekday(reference, dayOffset))) {
      continue;
    }

    for (const time of times) {
      const fireAt = localFireAt(reference, dayOffset, time);
      if (fireAt <= fromMs) continue;

      results.push(occurrenceAt(source, fireAt));
      if (results.length >= count) break;
    }
  }

  return results;
}

/**
 * The next `count` occurrences of `reminder` strictly after `fromMs`, in
 * chronological order.
 *
 * Returns fewer than `count` when the schedule cannot supply that many inside
 * its horizon: 60 local days for `daily`, 56 for `weekly`. `interval` has no
 * horizon and always returns exactly `count`.
 *
 * Throws on a schedule variant this engine does not handle, mirroring the C#
 * `InvalidOperationException`. That arm is unreachable from typed callers — the
 * `switch` is exhaustive over `ScheduleTimes` — and exists for values that came
 * from JSON or an untyped boundary.
 */
export function nextOccurrences(
  reminder: OccurrenceSource,
  fromMs: number,
  count: number = DEFAULT_COUNT
): readonly ScheduledOccurrence[] {
  if (!Number.isInteger(count) || count < 0) {
    throw new RangeError(
      `count must be a non-negative integer, received ${String(count)}.`
    );
  }
  if (count === 0) return [];

  const schedule = reminder.schedule;
  switch (schedule.type) {
    case "daily":
      return dailyOccurrences(reminder, schedule, fromMs, count);
    case "interval":
      return intervalOccurrences(reminder, schedule, fromMs, count);
    case "weekly":
      return weeklyOccurrences(reminder, schedule, fromMs, count);
    default:
      return assertNever(schedule);
  }
}
