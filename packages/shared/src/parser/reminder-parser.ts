/**
 * The natural-language reminder parser, ported from
 * `TaskRx.Core/ReminderParser.cs` (plus its `Patterns`, `Lexicon`,
 * `Extractors` and `State` partials) on the `archive/dotnet-maui` branch.
 *
 * It turns a phrase like `"atorvastatin 20mg every morning with food"` into a
 * `Reminder`. The grammar is small, deterministic and entirely offline — no
 * date library, no model call — because a medication reminder has to parse on a
 * phone with no signal, and has to parse the same way every time.
 *
 * **It never rejects an input.** A token it cannot place is collected into
 * `unparsedTokens` and the rest of the phrase still produces a reminder; the
 * only two failures are an input with no tokens at all and one where no token
 * could serve as a name. Nothing throws.
 *
 * Divergences from the C#, all deliberate:
 *
 * - **Purity.** `BuildReminder` reads `Guid.Empty`, `string.Empty`,
 *   `DateTimeOffset.UtcNow` (for `IntervalTimes`) and `default` timestamps.
 *   Those become `ParseReminderOptions`, so the same input parses to the same
 *   reminder forever, which is what makes this testable and replayable.
 *   `now` is used both as the interval anchor and as `createdAt`/`updatedAt`,
 *   where the C# left the timestamps at their zero value for a caller to fix up.
 * - **`undefined`, not `null`, means absent**, matching `Reminder.dosage` and
 *   the rest of this package.
 * - **The tokenizer keeps only the first alternative** of the C# regex
 *   `[a-zA-Z0-9\.\:]+|[0-9]+\.[0-9]+`. The second can never match: the first
 *   already accepts `.`, so it consumes `1.5` whole.
 * - **`IsNullOrWhiteSpace(input)` is folded into the zero-token check.** Both
 *   C# guards return the same error, and whitespace-only input tokenizes to
 *   nothing.
 * - **`BuildReminder`'s weekly re-normalization is dropped** (C# lines
 *   208-211). `ExtractFields` has already rebuilt the weekly schedule from the
 *   distinct, sorted days, so the second pass can only rebuild an identical
 *   value.
 *
 * C# behaviours kept as-is and flagged where they look wrong: `mergeTime`
 * dropping an `interval` schedule (see `patterns.ts`), and `N pills per dose`
 * yielding neither a pill count nor a dose (see `extractors.ts`).
 */

import type { Priority, ReminderKind } from "../domain/enums";
import type { CreateReminderInput, Reminder } from "../domain/reminder";
import { createReminder } from "../domain/reminder";
import type { ScheduleTimes, Weekday } from "../domain/schedule-times";
import { dailyTimes, weeklyTimes } from "../domain/schedule-times";

import {
  tryExtractPillsPerDose,
  tryExtractPillsRemaining,
  tryExtractRefillDays,
} from "./extractors";
import { isNoiseWord, isTaskVerb } from "./lexicon";
import {
  hasDrugSuffix,
  isWeekly,
  looksLikeDosage,
  mergeTime,
  tryParseAtTime,
  tryParseDaily,
  tryParseDayName,
  tryParseDosage,
  tryParseEvery,
  tryParseInstructions,
  tryParseNamedTime,
  tryParsePriority,
  tryParseRefill,
  tryParseTwiceDaily,
} from "./patterns";

/**
 * The ambient values `ReminderParser.BuildReminder` reaches for in C#, supplied
 * by the caller instead.
 */
export interface ParseReminderOptions {
  /** `Reminder.Id`, which the C# leaves as `Guid.Empty`. */
  readonly id: string;
  /** `Reminder.UserId`, which the C# leaves as `string.Empty`. */
  readonly userId: string;
  /**
   * Unix ms. Anchors an `every N hours` schedule (C# `DateTimeOffset.UtcNow`)
   * and stamps `createdAt`/`updatedAt`.
   */
  readonly now: number;
}

/** `ReminderParseResult(Reminder?, IReadOnlyList<string>, IReadOnlyList<string>)`. */
export interface ReminderParseResult {
  /** Absent only when `errors` is non-empty. */
  readonly reminder: Reminder | undefined;
  /** Tokens the grammar had no place for. A phrase still parses around them. */
  readonly unparsedTokens: readonly string[];
  readonly errors: readonly string[];
}

/** `ReminderParserState`: the accumulator the extraction pass writes into. */
interface ParserState {
  readonly tokens: readonly string[];
  readonly kind: ReminderKind;
  readonly now: number;
  name: string;
  dosage: string | undefined;
  instructions: string | undefined;
  schedule: ScheduleTimes | undefined;
  priority: Priority;
  readonly days: Weekday[];
  readonly unparsedTokens: string[];
  nameClosed: boolean;
}

const EMPTY_INPUT_ERROR = "Input is empty.";
const NO_NAME_ERROR = "Could not determine a name for the reminder.";

/** The fallback when a phrase names no time at all. */
const DEFAULT_TIME = "09:00";

/** Runs of letters, digits, dots and colons; everything else separates. */
const TOKEN_PATTERN = /[a-zA-Z0-9.:]+/g;

function tokenize(input: string): readonly string[] {
  return input.match(TOKEN_PATTERN) ?? [];
}

/**
 * Medication or chore.
 *
 * A dosage or a drug-class suffix settles it. Failing that, a leading task verb
 * marks a chore, and then length decides: a short phrase is a chore, a longer
 * one is assumed to name a drug.
 */
function detectKind(first: string, tokens: readonly string[]): ReminderKind {
  if (tokens.some(looksLikeDosage) || hasDrugSuffix(tokens)) {
    return "medication";
  }

  if (isTaskVerb(first)) return "task";

  const meaningful = tokens.filter((token) => !isNoiseWord(token)).length;
  return meaningful <= 3 ? "task" : "medication";
}

/**
 * Once a field has been recognized, the name is finished: later stray tokens
 * are collected as unparsed rather than appended to it.
 */
function closeNameIfSet(state: ParserState): void {
  if (state.name.trim() !== "") state.nameClosed = true;
}

function appendName(state: ParserState, token: string): void {
  state.name = state.name.trim() === "" ? token : `${state.name} ${token}`;
}

/** `state.Days.Distinct().OrderBy(d => d)`. */
function distinctSortedDays(days: readonly Weekday[]): readonly Weekday[] {
  return [...new Set(days)].sort((a, b) => a - b);
}

/**
 * Reads whatever starts at `index` and returns how many tokens it took, which
 * is at least one. The order of the attempts is the grammar's precedence and
 * matches `ReminderParser.ExtractFields` line for line.
 */
function consumeToken(
  state: ParserState,
  token: string,
  index: number
): number {
  const { tokens } = state;

  const dosage = tryParseDosage(token);
  if (dosage !== undefined) {
    state.dosage = dosage;
    closeNameIfSet(state);
    return 1;
  }

  const priority = tryParsePriority(token, tokens, index);
  if (priority !== undefined) {
    state.priority = priority.value;
    closeNameIfSet(state);
    return priority.consumed;
  }

  const every = tryParseEvery(token, tokens, index, state.now);
  if (every !== undefined) {
    state.schedule = every.value;
    closeNameIfSet(state);
    return every.consumed;
  }

  // `daily` yields to a schedule already in hand, so `twice daily` stays twice.
  const daily = tryParseDaily(token);
  if (daily !== undefined) {
    state.schedule ??= daily;
    closeNameIfSet(state);
    return 1;
  }

  const twiceDaily = tryParseTwiceDaily(token);
  if (twiceDaily !== undefined) {
    state.schedule = twiceDaily;
    closeNameIfSet(state);
    return 1;
  }

  const namedTime = tryParseNamedTime(token);
  if (namedTime !== undefined) {
    state.schedule = mergeTime(state.schedule, namedTime);
    closeNameIfSet(state);
    return 1;
  }

  const atTime = tryParseAtTime(token, tokens, index);
  if (atTime !== undefined) {
    state.schedule = mergeTime(state.schedule, atTime.value);
    closeNameIfSet(state);
    return atTime.consumed;
  }

  const day = tryParseDayName(token);
  if (day !== undefined) {
    state.days.push(day);
    state.schedule ??= weeklyTimes(state.days, []);
    closeNameIfSet(state);
    return 1;
  }

  if (isWeekly(token)) {
    state.schedule ??= weeklyTimes(state.days, []);
    closeNameIfSet(state);
    return 1;
  }

  const instructions = tryParseInstructions(token, tokens, index);
  if (instructions !== undefined) {
    state.instructions = instructions.value;
    closeNameIfSet(state);
    return instructions.consumed;
  }

  // The inventory numbers themselves are read later, from the whole token list.
  const refill = tryParseRefill(token, tokens, index);
  if (refill !== undefined) {
    closeNameIfSet(state);
    return refill;
  }

  if (isNoiseWord(token)) {
    // `take out trash` keeps its verb; `take atorvastatin` does not.
    if (state.kind === "task" && isTaskVerb(token)) {
      appendName(state, token);
    }
    return 1;
  }

  if (state.nameClosed) {
    state.unparsedTokens.push(token);
    return 1;
  }

  appendName(state, token);
  return 1;
}

/**
 * The C# walks the tokens with `while (i < tokens.Count)` and advances by a
 * variable `consumed`. Iterating `entries()` with a skip cursor is the same
 * traversal while keeping the current token narrowed to `string`.
 */
function extractFields(state: ParserState): void {
  let nextIndex = 0;
  for (const [index, token] of state.tokens.entries()) {
    if (index < nextIndex) continue;
    nextIndex = index + consumeToken(state, token, index);
  }

  // Days are collected as they are seen, so the weekly schedule built at the
  // first one has to be rebuilt from the full set.
  if (state.schedule?.type === "weekly") {
    state.schedule = weeklyTimes(
      distinctSortedDays(state.days),
      state.schedule.times
    );
  }
}

function buildReminder(
  state: ParserState,
  options: ParseReminderOptions
): Reminder {
  const { dosage, instructions } = state;
  const pillsRemaining = tryExtractPillsRemaining(state.tokens);
  const pillsPerDose = tryExtractPillsPerDose(state.tokens);
  const refillReminderDays = tryExtractRefillDays(state.tokens);

  // `exactOptionalPropertyTypes` draws a line between an absent property and
  // one explicitly set to `undefined`, so absent fields are spread in, not
  // assigned.
  const input: CreateReminderInput = {
    id: options.id,
    userId: options.userId,
    kind: state.kind,
    name: state.name.trim(),
    schedule: state.schedule ?? dailyTimes([DEFAULT_TIME]),
    priority: state.priority,
    createdAt: options.now,
    updatedAt: options.now,
    ...(dosage === undefined ? {} : { dosage }),
    ...(instructions === undefined ? {} : { instructions }),
    ...(pillsRemaining === undefined ? {} : { pillsRemaining }),
    ...(pillsPerDose === undefined ? {} : { pillsPerDose }),
    ...(refillReminderDays === undefined ? {} : { refillReminderDays }),
  };

  return createReminder(input);
}

/**
 * Parses `input` into a `Reminder`, plus the tokens it could not place.
 *
 * Pure: every value that is not derived from `input` comes from `options`.
 */
export function parseReminder(
  input: string,
  options: ParseReminderOptions
): ReminderParseResult {
  const tokens = tokenize(input);
  const first = tokens[0];
  if (first === undefined) {
    return {
      reminder: undefined,
      unparsedTokens: [],
      errors: [EMPTY_INPUT_ERROR],
    };
  }

  const state: ParserState = {
    tokens,
    kind: detectKind(first, tokens),
    now: options.now,
    name: "",
    dosage: undefined,
    instructions: undefined,
    schedule: undefined,
    priority: "normal",
    days: [],
    unparsedTokens: [],
    nameClosed: false,
  };

  extractFields(state);

  const unparsedTokens = [...state.unparsedTokens];
  if (state.name.trim() === "") {
    return { reminder: undefined, unparsedTokens, errors: [NO_NAME_ERROR] };
  }

  return {
    reminder: buildReminder(state, options),
    unparsedTokens,
    errors: [],
  };
}
