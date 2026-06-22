import type { Medication } from "../index.js";

import type { ScheduledDose } from "./types.js";

const MS_PER_MINUTE = 60_000;
const MS_PER_HOUR = 60 * MS_PER_MINUTE;
const MS_PER_DAY = 24 * MS_PER_HOUR;

/**
 * Parse a "HH:MM" string into { hour, minute } in local time.
 *
 * Returns undefined for malformed strings so the engine can ignore them
 * defensively.
 */
function parseTime(
  time: string
): { readonly hour: number; readonly minute: number } | undefined {
  const match = /^([0-9]{1,2}):([0-9]{2})$/.exec(time);
  if (match === null) return undefined;

  const [hourStr, minuteStr] = [match[1], match[2]];
  if (hourStr === undefined || minuteStr === undefined) return undefined;

  const hour = Number.parseInt(hourStr, 10);
  const minute = Number.parseInt(minuteStr, 10);

  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return undefined;

  return { hour, minute };
}

/**
 * Build a Date representing a local calendar date at local midnight from a
 * unix-ms timestamp. All calculations use the device's local timezone.
 */
function startOfLocalDay(ms: number): Date {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d;
}

/**
 * Compute the next N daily dose times after `fromMs`.
 *
 * Each `time` string is interpreted as "HH:MM" in the device's local timezone.
 * A dose occurring exactly at `fromMs` is excluded (the user already has that
 * moment). Times are sorted and wrapped across day boundaries.
 *
 * Pure function: no DB calls, no `Date.now()`, no globals.
 */
export function nextNDaily(
  times: readonly string[],
  fromMs: number,
  n: number
): readonly number[] {
  const count = Math.max(0, n);
  if (count === 0 || times.length === 0) return [];

  const parsed = times
    .map(parseTime)
    .filter((t): t is { hour: number; minute: number } => t !== undefined)
    .sort((a, b) => a.hour - b.hour || a.minute - b.minute);

  if (parsed.length === 0) return [];

  const result: number[] = [];
  const dayStart = startOfLocalDay(fromMs);
  let dayOffset = 0;

  while (result.length < count) {
    const currentDayMs = dayStart.getTime() + dayOffset * MS_PER_DAY;

    for (const { hour, minute } of parsed) {
      const candidate =
        currentDayMs + hour * MS_PER_HOUR + minute * MS_PER_MINUTE;
      if (candidate > fromMs) {
        result.push(candidate);
        if (result.length >= count) break;
      }
    }

    dayOffset += 1;
  }

  return result;
}

/**
 * Compute the next N interval dose times at or after `fromMs`.
 *
 * Doses start at `startAtMs` and recur every `intervalHours` hours. Doses
 * strictly before `fromMs` are skipped. The dose exactly at `fromMs` is
 * included.
 *
 * Pure function: no DB calls, no `Date.now()`, no globals.
 */
export function nextNInterval(
  intervalHours: number,
  startAtMs: number,
  fromMs: number,
  n: number
): readonly number[] {
  const count = Math.max(0, n);
  if (count === 0 || intervalHours <= 0) return [];

  const intervalMs = intervalHours * MS_PER_HOUR;
  const diff = fromMs - startAtMs;
  let index = Math.ceil(diff / intervalMs);
  if (index < 0) index = 0;

  const result: number[] = [];
  while (result.length < count) {
    result.push(startAtMs + index * intervalMs);
    index += 1;
  }

  return result;
}

/**
 * Compute the next N upcoming doses for a medication.
 *
 * Dispatches to `nextNDaily` or `nextNInterval` based on the medication's
 * `scheduleType`. Returns `ScheduledDose` objects tagged with the medication id.
 *
 * Pure function: no DB calls, no `Date.now()`, no globals.
 */
export function upcomingDosesFor(
  medication: Pick<Medication, "id" | "scheduleType" | "scheduleTimes">,
  fromMs: number,
  n: number
): readonly ScheduledDose[] {
  const count = Math.max(0, n);
  if (count === 0) return [];

  const scheduleType = medication.scheduleType as string;
  if (scheduleType !== "daily" && scheduleType !== "interval") {
    return [];
  }

  let times: readonly number[];

  if (medication.scheduleType === "daily") {
    const dailySchedule = medication.scheduleTimes as {
      readonly times: readonly string[];
    };
    times = nextNDaily(dailySchedule.times, fromMs, count);
  } else {
    const intervalSchedule = medication.scheduleTimes as {
      readonly hours: number;
      readonly startAt: number;
    };
    times = nextNInterval(
      intervalSchedule.hours,
      intervalSchedule.startAt,
      fromMs,
      count
    );
  }

  return times.map((scheduledTime) => ({
    medicationId: medication.id,
    scheduledTime,
  }));
}
