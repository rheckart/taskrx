package com.taskrx.core.parser

/*
 * The reminder parser's word lists, ported from
 * `TaskRx.Core/ReminderParserLexicon.cs` in the retired .NET app.
 *
 * The C# collections carry `StringComparer.OrdinalIgnoreCase`, so every lookup
 * is case-insensitive. Here the keys are stored lowercase and each accessor
 * lowercases its argument, which is the same thing for the ASCII-only tokens
 * this parser produces.
 *
 * `TimeOnly` becomes `"HH:MM"` and `DayOfWeek` becomes a weekday index
 * (Sunday = 0), matching the conventions in `domain/ScheduleTimes.kt`.
 */

/**
 * Words that carry no meaning on their own and never start a name.
 *
 * `every` is deliberately absent: it is consumed by the interval it introduces,
 * and an `every` that introduced nothing is a kicker the parser answers for by
 * itself (see `consumeToken`), not a word to hand back as unparsed.
 */
private val NOISE_WORDS: Set<String> =
    setOf("the", "a", "an", "my", "your", "at", "on", "in", "with", "for", "take", "please")

/**
 * Verbs that mark an input as a chore rather than a medication.
 *
 * Kept to the words that lead a chore and nothing else. `out` was here, which
 * made `take out the trash` a task only because of a particle — and everything
 * else starting `take` a task too. `take` still leads a dose someone phrased as
 * `take ibuprofen every 6 hours`, so a dosage or a drug suffix is checked
 * before any of these words are.
 */
private val TASK_VERBS: Set<String> =
    setOf("call", "pay", "buy", "schedule", "remind", "do", "pick", "take")

/**
 * Words that make a phrase *about* something other than a drug: a verb that
 * leads a chore, or one of the particles and nouns chores are made of.
 *
 * `take out the trash` names five words and is a chore; `Water the plants
 * downstairs` is a chore too. Counting words alone called both medications,
 * because "more than three words means a drug name" is a rule about drug
 * names and chores are written long as well.
 */
private val TASK_SIGNALS: Set<String> =
    TASK_VERBS +
        setOf(
            "out",
            "up",
            "away",
            "down",
            "trash",
            "garbage",
            "recycling",
            "laundry",
            "dishes",
            "plants",
        )

/**
 * Particles that finish a phrasal verb — `take out`, `pick up`, `put away`.
 *
 * Kept apart from [TASK_SIGNALS] because `put` is not a task verb: `put the bins
 * out` is a chore through its particle alone.
 */
private val NAME_PREFIX_PARTICLES: Set<String> = setOf("out", "up", "away", "down", "off", "back")

fun isNamePrefixVerb(token: String): Boolean = token.lowercase() in TASK_VERBS

fun isNamePrefixParticle(token: String): Boolean = token.lowercase() in NAME_PREFIX_PARTICLES

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

/**
 * Spelled-out counts, for `every two weeks` and the like.
 *
 * A person writes an interval as often in words as in digits, and the ported
 * grammar only ever read a digit — so `every two weeks` was read as a daily
 * reminder and left `two` and `weeks` in the name. A general English number
 * reader it is not: `twenty-one` is two tokens and stays unread, so it reads as
 * a bare `every` and falls back to the default schedule exactly as before.
 */
private val NUMBER_WORDS: Map<String, Int> =
    mapOf(
        "a" to 1,
        "an" to 1,
        "one" to 1,
        "two" to 2,
        "three" to 3,
        "four" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "ninety" to 90,
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

fun isTaskSignal(token: String): Boolean = token.lowercase() in TASK_SIGNALS

fun isDoseUnit(token: String): Boolean = token.lowercase() in DOSE_UNITS

fun lookupNamedTime(token: String): String? = NAMED_TIMES[token.lowercase()]

/** `two` is 2; `21` is null, because that is [parseIntToken]'s job. */
fun lookupNumberWord(token: String): Int? = NUMBER_WORDS[token.lowercase()]

fun lookupDayName(token: String): Int? = DAY_NAMES[token.lowercase()]
