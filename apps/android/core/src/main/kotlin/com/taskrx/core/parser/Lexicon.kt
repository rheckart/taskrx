package com.taskrx.core.parser

/*
 * The reminder parser's word lists, ported from
 * `TaskRx.Core/ReminderParserLexicon.cs` on the `archive/dotnet-maui` branch.
 *
 * The C# collections carry `StringComparer.OrdinalIgnoreCase`, so every lookup
 * is case-insensitive. Here the keys are stored lowercase and each accessor
 * lowercases its argument, which is the same thing for the ASCII-only tokens
 * this parser produces.
 *
 * `TimeOnly` becomes `"HH:MM"` and `DayOfWeek` becomes a weekday index
 * (Sunday = 0), matching the conventions in `domain/ScheduleTimes.kt`.
 */

/** Words that carry no meaning on their own and never start a name. */
private val NOISE_WORDS: Set<String> =
    setOf("the", "a", "an", "my", "your", "every", "at", "on", "in", "with", "for", "take", "please")

/** Verbs that mark an input as a chore rather than a medication. */
private val TASK_VERBS: Set<String> =
    setOf("call", "pay", "buy", "schedule", "remind", "do", "pick", "take", "out")

/**
 * Endings shared by whole drug classes — statins, ACE inhibitors, beta
 * blockers, ARBs, PPIs, diuretics, H2 blockers, benzodiazepines. A token ending
 * in one of these is treated as a drug name even without a dosage.
 */
val DRUG_SUFFIXES: List<String> =
    listOf("statin", "pril", "olol", "sartan", "prazole", "mide", "tidine", "azepam")

/** Colloquial times of day, as local wall-clock `"HH:MM"`. */
private val NAMED_TIMES: Map<String, String> =
    mapOf(
        "morning" to "08:00",
        "noon" to "12:00",
        "afternoon" to "14:00",
        "evening" to "18:00",
        "night" to "21:00",
        "bedtime" to "21:00",
        "breakfast" to "07:00",
        "lunch" to "12:00",
        "dinner" to "18:00",
    )

/** Full and abbreviated weekday names. Sunday is 0, as in C# and JS alike. */
private val DAY_NAMES: Map<String, Int> =
    mapOf(
        "monday" to 1,
        "mon" to 1,
        "tuesday" to 2,
        "tue" to 2,
        "tues" to 2,
        "wednesday" to 3,
        "wed" to 3,
        "thursday" to 4,
        "thu" to 4,
        "thur" to 4,
        "thurs" to 4,
        "friday" to 5,
        "fri" to 5,
        "saturday" to 6,
        "sat" to 6,
        "sunday" to 0,
        "sun" to 0,
    )

/**
 * The countable units an inventory phrase can use ("30 pills", "1 per dose").
 * The C# spells this set inline in both `ReminderParserPatterns.TryParseRefill`
 * and `ReminderParserExtractors.TryExtractPillsRemaining`; it is named once
 * here so the two cannot drift apart.
 */
private val DOSE_UNITS: Set<String> =
    setOf("pill", "pills", "tablet", "tablets", "capsule", "capsules")

fun isNoiseWord(token: String): Boolean = token.lowercase() in NOISE_WORDS

fun isTaskVerb(token: String): Boolean = token.lowercase() in TASK_VERBS

fun isDoseUnit(token: String): Boolean = token.lowercase() in DOSE_UNITS

fun lookupNamedTime(token: String): String? = NAMED_TIMES[token.lowercase()]

fun lookupDayName(token: String): Int? = DAY_NAMES[token.lowercase()]
