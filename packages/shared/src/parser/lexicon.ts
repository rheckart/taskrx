/**
 * The reminder parser's word lists, ported from
 * `TaskRx.Core/ReminderParserLexicon.cs` on the `archive/dotnet-maui` branch.
 *
 * The C# collections carry `StringComparer.OrdinalIgnoreCase`, so every lookup
 * is case-insensitive. Here the keys are stored lowercase and each accessor
 * lowercases its argument, which is the same thing for the ASCII-only tokens
 * this parser produces.
 *
 * `TimeOnly` becomes `"HH:MM"` and `DayOfWeek` becomes `Weekday`, matching the
 * conventions in `../domain/schedule-times`.
 */

import type { Weekday } from "../domain/schedule-times";

/** Words that carry no meaning on their own and never start a name. */
const NOISE_WORDS: ReadonlySet<string> = new Set([
  "the",
  "a",
  "an",
  "my",
  "your",
  "every",
  "at",
  "on",
  "in",
  "with",
  "for",
  "take",
  "please",
]);

/** Verbs that mark an input as a chore rather than a medication. */
const TASK_VERBS: ReadonlySet<string> = new Set([
  "call",
  "pay",
  "buy",
  "schedule",
  "remind",
  "do",
  "pick",
  "take",
  "out",
]);

/**
 * Endings shared by whole drug classes — statins, ACE inhibitors, beta
 * blockers, ARBs, PPIs, diuretics, H2 blockers, benzodiazepines. A token ending
 * in one of these is treated as a drug name even without a dosage.
 */
export const DRUG_SUFFIXES: readonly string[] = [
  "statin",
  "pril",
  "olol",
  "sartan",
  "prazole",
  "mide",
  "tidine",
  "azepam",
];

/** Colloquial times of day, as local wall-clock `"HH:MM"`. */
const NAMED_TIMES: ReadonlyMap<string, string> = new Map([
  ["morning", "08:00"],
  ["noon", "12:00"],
  ["afternoon", "14:00"],
  ["evening", "18:00"],
  ["night", "21:00"],
  ["bedtime", "21:00"],
  ["breakfast", "07:00"],
  ["lunch", "12:00"],
  ["dinner", "18:00"],
]);

/** Full and abbreviated weekday names. Sunday is 0, as in C# and JS alike. */
const DAY_NAMES: ReadonlyMap<string, Weekday> = new Map([
  ["monday", 1],
  ["mon", 1],
  ["tuesday", 2],
  ["tue", 2],
  ["tues", 2],
  ["wednesday", 3],
  ["wed", 3],
  ["thursday", 4],
  ["thu", 4],
  ["thur", 4],
  ["thurs", 4],
  ["friday", 5],
  ["fri", 5],
  ["saturday", 6],
  ["sat", 6],
  ["sunday", 0],
  ["sun", 0],
]);

/**
 * The countable units an inventory phrase can use ("30 pills", "1 per dose").
 * The C# spells this set inline in both `ReminderParserPatterns.TryParseRefill`
 * and `ReminderParserExtractors.TryExtractPillsRemaining`; it is named once
 * here so the two cannot drift apart.
 */
const DOSE_UNITS: ReadonlySet<string> = new Set([
  "pill",
  "pills",
  "tablet",
  "tablets",
  "capsule",
  "capsules",
]);

export function isNoiseWord(token: string): boolean {
  return NOISE_WORDS.has(token.toLowerCase());
}

export function isTaskVerb(token: string): boolean {
  return TASK_VERBS.has(token.toLowerCase());
}

export function isDoseUnit(token: string): boolean {
  return DOSE_UNITS.has(token.toLowerCase());
}

export function lookupNamedTime(token: string): string | undefined {
  return NAMED_TIMES.get(token.toLowerCase());
}

export function lookupDayName(token: string): Weekday | undefined {
  return DAY_NAMES.get(token.toLowerCase());
}
