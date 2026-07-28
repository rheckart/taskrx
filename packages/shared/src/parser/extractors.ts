/**
 * The inventory extractors, ported from
 * `TaskRx.Core/ReminderParserExtractors.cs` on the `archive/dotnet-maui`
 * branch.
 *
 * These re-scan the whole token list rather than reading state accumulated by
 * the main loop, exactly as the C# does: `ReminderParser.BuildReminder` calls
 * all three with `state.Tokens`.
 *
 * The C# loops are bounded by `i < tokens.Count - 1`; iterating `entries()`
 * instead keeps the current token narrowed to `string`, and the lookahead is
 * bounded by `tokenAt` returning `undefined` past the end.
 */

import { isDoseUnit } from "./lexicon";
import {
  equalsIgnoreCase,
  parseFloatToken,
  parseIntToken,
  tokenAt,
} from "./patterns";

/**
 * `30 pills` / `30 tablets`.
 *
 * `N <unit> per dose` is skipped, so that the `1` in `30 pills 1 per dose` is
 * read as the dose and only the `30` as the count.
 */
export function tryExtractPillsRemaining(
  tokens: readonly string[]
): number | undefined {
  for (const [index, token] of tokens.entries()) {
    const count = parseIntToken(token);
    const next = tokenAt(tokens, index + 1);
    if (count === undefined || next === undefined) continue;
    if (!isDoseUnit(next)) continue;

    if (
      equalsIgnoreCase(tokenAt(tokens, index + 2), "per") &&
      equalsIgnoreCase(tokenAt(tokens, index + 3), "dose")
    ) {
      continue;
    }

    return count;
  }

  return undefined;
}

/**
 * `1 per dose`, `1.5 per dose`.
 *
 * Read as a `double` where the phrase parser (`tryParseRefill`) only accepts an
 * `int`, which is the C# behaviour: `1.5 per dose` sets the dose but leaves
 * `1.5` in the unparsed tokens.
 */
export function tryExtractPillsPerDose(
  tokens: readonly string[]
): number | undefined {
  for (const [index, token] of tokens.entries()) {
    const amount = parseFloatToken(token);
    if (
      amount !== undefined &&
      equalsIgnoreCase(tokenAt(tokens, index + 1), "per") &&
      equalsIgnoreCase(tokenAt(tokens, index + 2), "dose")
    ) {
      return amount;
    }
  }

  return undefined;
}

/** `refill in 7` / `refill in 7 days`. */
export function tryExtractRefillDays(
  tokens: readonly string[]
): number | undefined {
  for (const [index, token] of tokens.entries()) {
    if (!equalsIgnoreCase(token, "refill")) continue;
    if (!equalsIgnoreCase(tokenAt(tokens, index + 1), "in")) continue;

    const days = parseIntToken(tokenAt(tokens, index + 2));
    if (days !== undefined) return days;
  }

  return undefined;
}
