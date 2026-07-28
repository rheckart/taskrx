/**
 * The natural-language schedule grammar parser, ported from
 * `ScheduleGrammarParser` in `TaskRx.Core/ScheduleGrammarParser.cs` on the
 * `archive/dotnet-maui` branch.
 *
 * Turns phrases like `"MWF at 09:00"` or `"every day at 8am, 8pm"` into a day
 * set plus a time set. It deliberately stays a *grammar* parser: it recognizes
 * the schedule half of a command and reports which tokens it could not
 * understand, leaving the free-text half (a medication name, a dosage) to the
 * caller.
 *
 * Three shape changes from the C#, all forced by the type system rather than
 * chosen:
 *
 * - **The result is a tagged union**, not one record with an `IsSuccess` flag.
 *   The C# `ScheduleParseResult` carries `DaysOfWeek`/`Times`/`Error`/
 *   `UnparseableTokens` on every instance, so a caller can read `Error` off a
 *   success and get `null`. Splitting it means the compiler will not let you
 *   read a failure's fields without narrowing first.
 * - **`TimeOnly` → `"HH:MM"`**, the local wall-clock string the rest of this
 *   package uses. `TimeOnly` cannot hold an invalid time; the string form can,
 *   so every time is range-checked here and emitted zero-padded, which also
 *   makes lexicographic order chronological order.
 * - **`int[]?` → `readonly Weekday[] | null`.** The `null` still means "daily"
 *   — see `daysOfWeek` below — but the elements are now the domain's `Weekday`,
 *   so a day index can no longer be an arbitrary `int`.
 *
 * Pure functions: no `Date.now()`, no locale or timezone dependence.
 */

import type { Weekday } from "../domain/schedule-times";

/** `ScheduleParseResult` with `IsSuccess = true` (C# line 34). */
export interface ScheduleParseSuccess {
  readonly type: "success";
  /**
   * `null` = every day. Otherwise a sorted, de-duplicated set of weekdays.
   *
   * A day spec that names no specific day (`daily`, `every day`) is exactly
   * what produces `null`; an input with no day spec at all is also `null`,
   * because "at 08:00" means every day too.
   */
  readonly daysOfWeek: readonly Weekday[] | null;
  /** Sorted, de-duplicated `"HH:MM"` times. Empty for a days-only input. */
  readonly times: readonly string[];
}

/** `ScheduleParseResult` with `IsSuccess = false` (C# line 41). */
export interface ScheduleParseFailure {
  readonly type: "failure";
  /** Human-readable failure reason. */
  readonly error: string;
  /** Exactly the tokens that could not be understood. */
  readonly unparseableTokens: readonly string[];
}

export type ScheduleParseResult = ScheduleParseSuccess | ScheduleParseFailure;

/** Day name → weekday (C# `DayNames`). Keys are already lowercased. */
const DAY_NAMES = new Map<string, Weekday>([
  ["sun", 0],
  ["sunday", 0],
  ["mon", 1],
  ["monday", 1],
  ["tue", 2],
  ["tues", 2],
  ["tuesday", 2],
  ["wed", 3],
  ["wednesday", 3],
  ["thu", 4],
  ["thur", 4],
  ["thurs", 4],
  ["thursday", 4],
  ["fri", 5],
  ["friday", 5],
  ["sat", 6],
  ["saturday", 6],
]);

/** Two-char letter codes, matched first so `t`/`s` are never ambiguous. */
const TWO_CHAR_CODES = new Map<string, Weekday>([
  ["tu", 2],
  ["th", 4],
  ["sa", 6],
  ["su", 0],
]);

/** One-char letter codes — the days no other day starts with. */
const ONE_CHAR_CODES = new Map<string, Weekday>([
  ["m", 1],
  ["w", 3],
  ["f", 5],
]);

/** The bare keywords, for `isGrammarToken` (C# line 105). */
const KEYWORDS = new Set([
  "every",
  "at",
  "and",
  "daily",
  "day",
  "weekdays",
  "weekends",
]);

const WEEKDAY_DAYS: readonly Weekday[] = [1, 2, 3, 4, 5];
const WEEKEND_DAYS: readonly Weekday[] = [0, 6];

/** 12-hour: `8am`, `8:30pm`, `8a.m.` (C# `AmPmPattern`). */
const AM_PM_PATTERN = /^([0-9]{1,2})(?::([0-9]{2}))?\s*(am|pm|a\.m\.|p\.m\.)$/;

/**
 * 24-hour: `08:00`, `8:00`, `8:0`.
 *
 * The C# tries `HH:mm`, `H:mm` and `H:m` in turn; .NET's custom-format parsing
 * accepts one or two digits for a single `H`/`m`, so the three collapse into
 * one pattern with the range check that `TryParseExact` would have done.
 */
const HOUR_MINUTE_PATTERN = /^([0-9]{1,2}):([0-9]{1,2})$/;

/**
 * Bare hour: `8`, `20`.
 *
 * The sign is here because `int.TryParse` accepts one, so `"+8"` parses in the
 * C# and `"-1"` is rejected by the range check rather than by the format.
 */
const BARE_HOUR_PATTERN = /^[+-]?[0-9]+$/;

function formatTime(hour: number, minute: number): string {
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function failure(
  error: string,
  unparseableTokens: readonly string[]
): ScheduleParseFailure {
  return { type: "failure", error, unparseableTokens: [...unparseableTokens] };
}

/**
 * Lowercase, treat commas as spaces, split on spaces and tabs.
 *
 * Only spaces and tabs separate, matching the C# `Split`, so a token is never
 * empty but may still hold an odd character — that is the unparseable path's
 * job, not the tokenizer's.
 */
function tokenize(input: string): readonly string[] {
  return input
    .toLowerCase()
    .replaceAll(",", " ")
    .split(/[ \t]/)
    .map((token) => token.trim())
    .filter((token) => token.length > 0);
}

/**
 * Greedily parse a run-together day-letter combo (`mwf`, `tuth`, `mtwtf`).
 *
 * Two-char codes win over one-char at each position, so `tu` (Tue) and `th`
 * (Thu) are consumed before a bare `t` — which is not a code at all, precisely
 * because it would be ambiguous.
 *
 * The C# guards this call with `IsAllLetters`; that guard lives inside here
 * instead. It also subsumes the C#'s trailing `days.Count > 0` check, which
 * could only ever fire for an empty token.
 */
function parseLetterCombo(token: string): readonly Weekday[] | undefined {
  if (!/^[a-z]+$/.test(token)) return undefined;

  const days: Weekday[] = [];
  let index = 0;
  while (index < token.length) {
    // A one-character tail slices short and can never match a two-char code,
    // so the C#'s explicit `i + 2 <= token.Length` bound is implicit here.
    const two = TWO_CHAR_CODES.get(token.slice(index, index + 2));
    if (two !== undefined) {
      days.push(two);
      index += 2;
      continue;
    }

    const one = ONE_CHAR_CODES.get(token.slice(index, index + 1));
    if (one !== undefined) {
      days.push(one);
      index += 1;
      continue;
    }

    return undefined;
  }

  return days;
}

/**
 * Parse a single time token: 12-hour (`8am`, `8:30pm`), 24-hour (`08:00`), or
 * bare hour (`8`). Returns `"HH:MM"`, or `undefined` if the token is not a
 * time or names one that does not exist.
 *
 * Note that `"8 am"` never reaches here as one token — the tokenizer splits it
 * — even though the C# regex allows the space and its doc comment advertises
 * the form. Ported as-is; see the test suite for the resulting behaviour.
 */
function parseTime(input: string): string | undefined {
  const token = input.trim().toLowerCase();

  const amPm = AM_PM_PATTERN.exec(token);
  if (amPm !== null) {
    const [, hourText, minuteText, suffix] = amPm;
    /* v8 ignore next */
    if (hourText === undefined || suffix === undefined) return undefined;

    let hour = Number.parseInt(hourText, 10);
    const minute =
      minuteText === undefined ? 0 : Number.parseInt(minuteText, 10);
    const isPm = suffix.replaceAll(".", "") === "pm";

    if (isPm && hour < 12) hour += 12;
    if (!isPm && hour === 12) hour = 0; // 12am → 00:00

    // Only the upper bounds are checked: the pattern admits digits only, so
    // the C#'s `>= 0` halves can never fail.
    if (hour < 24 && minute < 60) return formatTime(hour, minute);

    // An am/pm token that names no real time is rejected outright, rather than
    // falling through to the 24-hour forms it cannot match anyway.
    return undefined;
  }

  const hourMinute = HOUR_MINUTE_PATTERN.exec(token);
  if (hourMinute !== null) {
    const [, hourText, minuteText] = hourMinute;
    /* v8 ignore next */
    if (hourText === undefined || minuteText === undefined) return undefined;

    const hour = Number.parseInt(hourText, 10);
    const minute = Number.parseInt(minuteText, 10);
    // A token with a colon can never parse as a bare hour, so failing here is
    // the same as falling through to the C#'s `int.TryParse`.
    if (hour < 24 && minute < 60) return formatTime(hour, minute);
    return undefined;
  }

  if (BARE_HOUR_PATTERN.test(token)) {
    const hour = Number.parseInt(token, 10);
    if (hour >= 0 && hour < 24) return formatTime(hour, 0);
  }

  return undefined;
}

/**
 * True when a token looks like schedule grammar — a keyword, day name, letter
 * combo, or time — rather than free text like a medication name.
 *
 * Used to split a command at the first grammar-like token, which is what keeps
 * a multi-word name intact.
 */
export function isGrammarToken(token: string): boolean {
  if (token.trim() === "") return false;

  const normalized = token.toLowerCase();
  if (KEYWORDS.has(normalized)) return true;
  if (DAY_NAMES.has(normalized)) return true;
  if (parseLetterCombo(normalized) !== undefined) return true;
  return parseTime(normalized) !== undefined;
}

/**
 * Parse a schedule phrase into days and times.
 *
 * Fails in three ways, in this order: no input at all; at least one token that
 * could not be understood (reported individually, so the caller can quote
 * them); or an input that parsed cleanly but said nothing — `"at and"` is all
 * separators and no schedule.
 */
export function parseScheduleGrammar(
  input: string | null | undefined
): ScheduleParseResult {
  if (input === null || input === undefined || input.trim() === "") {
    return failure("No schedule text provided.", []);
  }

  const tokens = tokenize(input);

  const days = new Set<Weekday>();
  const times = new Set<string>();
  const unparseable: string[] = [];
  let daySpecSeen = false;

  for (const token of tokens) {
    switch (token) {
      case "every":
      case "daily":
      case "day":
        // These assert only that a day spec exists. Leaving the day set empty
        // is what makes `daysOfWeek` come out `null`, i.e. every day.
        daySpecSeen = true;
        continue;
      case "at":
      case "and":
        continue;
      case "weekdays":
        daySpecSeen = true;
        for (const day of WEEKDAY_DAYS) days.add(day);
        continue;
      case "weekends":
        daySpecSeen = true;
        for (const day of WEEKEND_DAYS) days.add(day);
        continue;
      default:
        break;
    }

    const named = DAY_NAMES.get(token);
    if (named !== undefined) {
      daySpecSeen = true;
      days.add(named);
      continue;
    }

    const time = parseTime(token);
    if (time !== undefined) {
      times.add(time);
      continue;
    }

    // Last chance: a run-together letter combo (mwf, tuth, mtwtf).
    const combo = parseLetterCombo(token);
    if (combo !== undefined) {
      daySpecSeen = true;
      for (const day of combo) days.add(day);
      continue;
    }

    unparseable.push(token);
  }

  if (unparseable.length > 0) {
    return failure(
      `Couldn't parse: ${unparseable.join(", ")}. Use a day spec (daily/weekdays/weekends/Mon…/MWF) and times like 08:00 or 8am.`,
      unparseable
    );
  }

  if (!daySpecSeen && times.size === 0) {
    return failure(
      "Couldn't parse the schedule. Try 'daily at 08:00' or 'MWF at 09:00'.",
      tokens
    );
  }

  return {
    type: "success",
    daysOfWeek:
      daySpecSeen && days.size > 0
        ? [...days].sort((left, right) => left - right)
        : null,
    // Zero-padded, so string order is clock order.
    times: [...times].sort((left, right) => left.localeCompare(right)),
  };
}
