/**
 * The reminder parser's token patterns, ported from
 * `TaskRx.Core/ReminderParserPatterns.cs` on the `archive/dotnet-maui` branch.
 *
 * The C# uses `bool TryX(..., out T value, out int consumed)`. Here each
 * function returns `undefined` for "no match" and a `Match<T>` (or a plain
 * count, where the C# only reports how many tokens were swallowed) otherwise,
 * so a match cannot be read without having been checked.
 *
 * Two shape changes are worth naming:
 *
 * - **The current token is passed separately from the token list.** Under
 *   `noUncheckedIndexedAccess`, `tokens[index]` is `string | undefined`, and
 *   re-checking a token the caller has already narrowed would add a branch that
 *   can never be taken. Lookahead still goes through `tokenAt`, where
 *   `undefined` genuinely means "past the end of the input".
 * - **Clock parsing is an explicit grammar.** The C# first tries
 *   `TimeOnly.TryParse` with the invariant culture and then four exact formats
 *   (`h:mmtt`, `htt`, `h:mm`, `H:mm`). `parseClockTime` implements
 *   `h[:mm[:ss]][am|pm]` directly rather than reproducing .NET's culture-driven
 *   parser: `"8am"`, `"8:30pm"`, `"8:00"` and `"18:30"` all parse, a bare
 *   `"8"` does not, and seconds are accepted but truncated because the domain
 *   keeps minutes. A single-digit minute (`"8:5"`), which .NET's general parse
 *   may accept, is not supported here.
 */

import type { Priority } from "../domain/enums";
import type { ScheduleTimes, Weekday } from "../domain/schedule-times";
import {
  dailyTimes,
  intervalTimes,
  weeklyTimes,
} from "../domain/schedule-times";

import {
  DRUG_SUFFIXES,
  isDoseUnit,
  lookupDayName,
  lookupNamedTime,
} from "./lexicon";

/** A parsed value plus the number of tokens it swallowed (the C# `consumed`). */
export interface Match<T> {
  readonly value: T;
  readonly consumed: number;
}

/** `\d+(\.\d+)?\s*(mg|...)`, unanchored, as in the C# `DosageRegex`. */
const DOSAGE_PATTERN =
  /\d+(\.\d+)?\s*(mg|mcg|g|ml|tablets?|capsules?|pills?|units?)/i;

/** `h[:mm[:ss]][am|pm]`. Seconds are matched so they can be discarded. */
const CLOCK_PATTERN = /^(\d{1,2})(?::(\d{2}))?(?::\d{2})?(am|pm)?$/i;

/** Tokens can only be `[a-zA-Z0-9.:]+`, so no sign is ever present. */
const INT_TOKEN_PATTERN = /^\d+$/;

/** `NumberStyles.Float` minus the signs the tokenizer cannot produce. */
const FLOAT_TOKEN_PATTERN = /^(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;

/** `int.TryParse` fails above this, which the port has to match. */
const INT32_MAX = 2_147_483_647;

const HOUR_UNITS: ReadonlySet<string> = new Set(["hour", "hours"]);
const DAY_UNITS: ReadonlySet<string> = new Set(["day", "days"]);

/** `(first, second, value)` from `TryParseInstructions`. */
const INSTRUCTION_PHRASES = [
  ["with", "food", "with food"],
  ["with", "water", "with water"],
  ["without", "food", "without food"],
  ["after", "meals", "after meals"],
  ["before", "meals", "before meals"],
] as const;

/** Lookahead. `undefined` means the index is past the end of the input. */
export function tokenAt(
  tokens: readonly string[],
  index: number
): string | undefined {
  return tokens[index];
}

/** `string.Equals(x, y, StringComparison.OrdinalIgnoreCase)`. */
export function equalsIgnoreCase(
  value: string | undefined,
  lowercaseExpected: string
): boolean {
  return value?.toLowerCase() === lowercaseExpected;
}

/** `int.TryParse`, including the `Int32` range check. */
export function parseIntToken(token: string | undefined): number | undefined {
  if (token === undefined || !INT_TOKEN_PATTERN.test(token)) return undefined;

  const value = Number.parseInt(token, 10);
  return value > INT32_MAX ? undefined : value;
}

/** `double.TryParse(..., NumberStyles.Float, InvariantCulture, ...)`. */
export function parseFloatToken(token: string): number | undefined {
  return FLOAT_TOKEN_PATTERN.test(token) ? Number(token) : undefined;
}

function pad(value: number): string {
  return String(value).padStart(2, "0");
}

function parseClockTime(token: string): string | undefined {
  const match = CLOCK_PATTERN.exec(token);
  if (match === null) return undefined;

  const [, hourText, minuteText, designator] = match;
  /* v8 ignore next -- group 1 is not optional; cf. occurrences.ts:75 */
  if (hourText === undefined) return undefined;

  let hour = Number.parseInt(hourText, 10);
  const minute = minuteText === undefined ? 0 : Number.parseInt(minuteText, 10);
  if (minute > 59) return undefined;

  if (designator === undefined) {
    // A lone number is a count, not a time: `TimeOnly.TryParse("8")` fails and
    // none of the four exact formats match it either.
    if (minuteText === undefined) return undefined;
    if (hour > 23) return undefined;
  } else {
    if (hour < 1 || hour > 12) return undefined;

    const isAfternoon = designator.toLowerCase() === "pm";
    if (hour === 12) {
      hour = isAfternoon ? 12 : 0;
    } else if (isAfternoon) {
      hour += 12;
    }
  }

  return `${pad(hour)}:${pad(minute)}`;
}

export function looksLikeDosage(token: string): boolean {
  return DOSAGE_PATTERN.test(token);
}

export function hasDrugSuffix(tokens: readonly string[]): boolean {
  return tokens.some((token) =>
    DRUG_SUFFIXES.some((suffix) => token.toLowerCase().endsWith(suffix))
  );
}

/** The whole token becomes the dosage, verbatim, as in the C#. */
export function tryParseDosage(token: string): string | undefined {
  return looksLikeDosage(token) ? token : undefined;
}

export function tryParsePriority(
  token: string,
  tokens: readonly string[],
  index: number
): Match<Priority> | undefined {
  if (equalsIgnoreCase(token, "urgent")) {
    return { value: "critical", consumed: 1 };
  }

  if (equalsIgnoreCase(token, "important")) {
    return { value: "high", consumed: 1 };
  }

  if (
    equalsIgnoreCase(token, "low") &&
    equalsIgnoreCase(tokenAt(tokens, index + 1), "priority")
  ) {
    return { value: "low", consumed: 2 };
  }

  return undefined;
}

/**
 * `every N hours`, `every N days`, `every <named time>`, `every <weekday>`.
 *
 * `now` replaces the C#'s `DateTimeOffset.UtcNow`, which is the parser's only
 * reach for ambient state; taking it as an argument is what keeps this pure.
 *
 * The weekday form consumes only `every` so the day itself is read by the main
 * loop, which is how a run of days accumulates.
 */
export function tryParseEvery(
  token: string,
  tokens: readonly string[],
  index: number,
  now: number
): Match<ScheduleTimes> | undefined {
  const next = tokenAt(tokens, index + 1);
  if (next === undefined || !equalsIgnoreCase(token, "every")) return undefined;

  const count = parseIntToken(next);
  if (count !== undefined) {
    const unit = tokenAt(tokens, index + 2)?.toLowerCase();
    if (unit !== undefined && HOUR_UNITS.has(unit)) {
      return { value: intervalTimes(count, now), consumed: 3 };
    }
    if (unit !== undefined && DAY_UNITS.has(unit)) {
      return { value: intervalTimes(count * 24, now), consumed: 3 };
    }
    return undefined;
  }

  const namedTime = lookupNamedTime(next);
  if (namedTime !== undefined) {
    return { value: dailyTimes([namedTime]), consumed: 2 };
  }

  if (lookupDayName(next) !== undefined) {
    return { value: weeklyTimes([], []), consumed: 1 };
  }

  return undefined;
}

/** `daily` on its own means 09:00. */
export function tryParseDaily(token: string): ScheduleTimes | undefined {
  return equalsIgnoreCase(token, "daily") ? dailyTimes(["09:00"]) : undefined;
}

/** `twice` means 08:00 and 18:00, whether or not `daily` follows. */
export function tryParseTwiceDaily(token: string): ScheduleTimes | undefined {
  return equalsIgnoreCase(token, "twice")
    ? dailyTimes(["08:00", "18:00"])
    : undefined;
}

export function tryParseNamedTime(token: string): string | undefined {
  return lookupNamedTime(token);
}

/** A clock time, with or without a leading `at`. */
export function tryParseAtTime(
  token: string,
  tokens: readonly string[],
  index: number
): Match<string> | undefined {
  const leadingAt = equalsIgnoreCase(token, "at");
  const candidate = leadingAt ? tokenAt(tokens, index + 1) : token;
  if (candidate === undefined) return undefined;

  const time = parseClockTime(candidate);
  if (time === undefined) return undefined;

  return { value: time, consumed: leadingAt ? 2 : 1 };
}

export function tryParseDayName(token: string): Weekday | undefined {
  return lookupDayName(token);
}

export function isWeekly(token: string): boolean {
  return equalsIgnoreCase(token, "weekly");
}

export function tryParseInstructions(
  token: string,
  tokens: readonly string[],
  index: number
): Match<string> | undefined {
  const next = tokenAt(tokens, index + 1);
  if (next === undefined) return undefined;

  for (const [first, second, value] of INSTRUCTION_PHRASES) {
    if (equalsIgnoreCase(token, first) && equalsIgnoreCase(next, second)) {
      return { value, consumed: 2 };
    }
  }

  return undefined;
}

/**
 * Inventory phrases — `N pills`, `N per dose`, `per dose`, `refill in N days`.
 *
 * Nothing is returned but a token count: the values themselves are read from
 * the whole token list by the extractors, so this only says how much of the
 * input the phrase covers. `N pills per dose` deliberately does not match, so
 * that `1 per dose` in `30 pills 1 per dose` is not misread as a pill count —
 * see `tryExtractPillsRemaining`, which skips the same shape.
 */
export function tryParseRefill(
  token: string,
  tokens: readonly string[],
  index: number
): number | undefined {
  const next = tokenAt(tokens, index + 1);

  if (parseIntToken(token) !== undefined && next !== undefined) {
    if (isDoseUnit(next)) {
      if (
        equalsIgnoreCase(tokenAt(tokens, index + 2), "per") &&
        equalsIgnoreCase(tokenAt(tokens, index + 3), "dose")
      ) {
        return undefined;
      }
      return 2;
    }

    if (
      equalsIgnoreCase(next, "per") &&
      equalsIgnoreCase(tokenAt(tokens, index + 2), "dose")
    ) {
      return 3;
    }
  }

  if (equalsIgnoreCase(token, "per") && equalsIgnoreCase(next, "dose")) {
    return 2;
  }

  if (
    equalsIgnoreCase(token, "refill") &&
    equalsIgnoreCase(next, "in") &&
    parseIntToken(tokenAt(tokens, index + 2)) !== undefined
  ) {
    return equalsIgnoreCase(tokenAt(tokens, index + 3), "days") ? 4 : 3;
  }

  return undefined;
}

/** `Times.Append(time).Distinct().OrderBy(t => t)`, on `"HH:MM"` strings. */
function mergeTimes(times: readonly string[], time: string): readonly string[] {
  return [...new Set([...times, time])].sort((a, b) => a.localeCompare(b));
}

/**
 * Folds a time into whatever schedule has been built so far.
 *
 * An `interval` schedule is replaced by a fresh `daily` one rather than being
 * merged into, so `every 8 hours at 9am` ends up as a 09:00 daily dose. That is
 * what `ReminderParserPatterns.MergeTime` does — its two type tests fall
 * through to `new DailyTimes(...)` — and it looks like a bug, but the port keeps
 * it: changing it here would silently change what an existing phrase schedules.
 */
export function mergeTime(
  schedule: ScheduleTimes | undefined,
  time: string
): ScheduleTimes {
  if (schedule?.type === "daily") {
    return dailyTimes(mergeTimes(schedule.times, time));
  }

  if (schedule?.type === "weekly") {
    return weeklyTimes(schedule.daysOfWeek, mergeTimes(schedule.times, time));
  }

  return dailyTimes([time]);
}
