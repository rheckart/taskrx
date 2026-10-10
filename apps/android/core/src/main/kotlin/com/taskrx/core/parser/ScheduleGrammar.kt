package com.taskrx.core.parser

/*
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
 * Shape changes from the C#:
 *
 * - **The result is a sealed type**, not one record with an `IsSuccess` flag.
 *   The C# `ScheduleParseResult` carries `DaysOfWeek`/`Times`/`Error`/
 *   `UnparseableTokens` on every instance, so a caller can read `Error` off a
 *   success and get `null`. Splitting it means the compiler will not let you
 *   read a failure's fields without checking which one it is.
 * - **`TimeOnly` becomes `"HH:MM"`**, the local wall-clock string the rest of
 *   this package uses. `TimeOnly` cannot hold an invalid time; the string form
 *   can, so every time is range-checked here and emitted zero-padded, which
 *   also makes lexicographic order chronological order.
 * - **`int[]?` becomes `List<Int>?`.** The `null` still means "daily" — see
 *   [ScheduleParseResult.Success.daysOfWeek].
 *
 * Pure functions: no clock, no locale or timezone dependence.
 */

sealed interface ScheduleParseResult {
    /** `ScheduleParseResult` with `IsSuccess = true`. */
    data class Success(
        /**
         * `null` = every day. Otherwise a sorted, de-duplicated set of weekdays.
         *
         * A day spec that names no specific day (`daily`, `every day`) is exactly
         * what produces `null`; an input with no day spec at all is also `null`,
         * because "at 08:00" means every day too.
         */
        val daysOfWeek: List<Int>?,
        /** Sorted, de-duplicated `"HH:MM"` times. Empty for a days-only input. */
        val times: List<String>,
    ) : ScheduleParseResult

    /** `ScheduleParseResult` with `IsSuccess = false`. */
    data class Failure(
        /** Human-readable failure reason. */
        val error: String,
        /** Exactly the tokens that could not be understood. */
        val unparseableTokens: List<String>,
    ) : ScheduleParseResult
}

/** Day name -> weekday (C# `DayNames`). Keys are already lowercased. */
private val GRAMMAR_DAY_NAMES: Map<String, Int> =
    mapOf(
        "sun" to 0,
        "sunday" to 0,
        "mon" to 1,
        "monday" to 1,
        "tue" to 2,
        "tues" to 2,
        "tuesday" to 2,
        "wed" to 3,
        "wednesday" to 3,
        "thu" to 4,
        "thur" to 4,
        "thurs" to 4,
        "thursday" to 4,
        "fri" to 5,
        "friday" to 5,
        "sat" to 6,
        "saturday" to 6,
    )

/** Two-char letter codes, matched first so `t`/`s` are never ambiguous. */
private val TWO_CHAR_CODES: Map<String, Int> = mapOf("tu" to 2, "th" to 4, "sa" to 6, "su" to 0)

/** One-char letter codes — the days no other day starts with. */
private val ONE_CHAR_CODES: Map<String, Int> = mapOf("m" to 1, "w" to 3, "f" to 5)

/** The bare keywords, for [isGrammarToken]. */
private val KEYWORDS: Set<String> = setOf("every", "at", "and", "daily", "day", "weekdays", "weekends")

private val WEEKDAY_DAYS: List<Int> = listOf(1, 2, 3, 4, 5)
private val WEEKEND_DAYS: List<Int> = listOf(0, 6)

/** 12-hour: `8am`, `8:30pm`, `8a.m.` (C# `AmPmPattern`). */
private val AM_PM_PATTERN =
    Regex(
        // `\s*` in JS is the ECMAScript whitespace set, wider than Java's `\s`.
        """([0-9]{1,2})(?::([0-9]{2}))?[\t\n\x0B\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]*""" +
            """(am|pm|a\.m\.|p\.m\.)""",
    )

/**
 * 24-hour: `08:00`, `8:00`, `8:0`.
 *
 * The C# tries `HH:mm`, `H:mm` and `H:m` in turn; .NET's custom-format parsing
 * accepts one or two digits for a single `H`/`m`, so the three collapse into
 * one pattern with the range check that `TryParseExact` would have done.
 */
private val HOUR_MINUTE_PATTERN = Regex("""([0-9]{1,2}):([0-9]{1,2})""")

/**
 * Bare hour: `8`, `20`.
 *
 * The sign is here because `int.TryParse` accepts one, so `"+8"` parses in the
 * C# and `"-1"` is rejected by the range check rather than by the format.
 */
private val BARE_HOUR_PATTERN = Regex("""[+-]?[0-9]+""")

private val LETTERS_PATTERN = Regex("""[a-z]+""")

private fun formatTime(hour: Int, minute: Int): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

private fun failure(error: String, unparseableTokens: List<String>): ScheduleParseResult.Failure =
    ScheduleParseResult.Failure(error, unparseableTokens.toList())

/**
 * Lowercase, treat commas as spaces, split on spaces and tabs.
 *
 * Only spaces and tabs separate, matching the C# `Split`, so a token is never
 * empty but may still hold an odd character — that is the unparseable path's
 * job, not the tokenizer's.
 */
private fun tokenize(input: String): List<String> =
    input
        .lowercase()
        .replace(",", " ")
        .split(' ', '\t')
        .map(::jsTrim)
        .filter { it.isNotEmpty() }

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
private fun parseLetterCombo(token: String): List<Int>? {
    if (!LETTERS_PATTERN.matches(token)) return null

    val days = mutableListOf<Int>()
    var index = 0
    while (index < token.length) {
        // A one-character tail slices short and can never match a two-char code,
        // so the C#'s explicit `i + 2 <= token.Length` bound is implicit here.
        val two = TWO_CHAR_CODES[token.substring(index, minOf(index + 2, token.length))]
        if (two != null) {
            days.add(two)
            index += 2
            continue
        }

        val one = ONE_CHAR_CODES[token.substring(index, index + 1)]
        if (one != null) {
            days.add(one)
            index += 1
            continue
        }

        return null
    }

    return days
}

/**
 * Parse a single time token: 12-hour (`8am`, `8:30pm`), 24-hour (`08:00`), or
 * bare hour (`8`). Returns `"HH:MM"`, or `null` if the token is not a time or
 * names one that does not exist.
 *
 * Note that `"8 am"` never reaches here as one token — the tokenizer splits it
 * — even though the C# regex allows the space and its doc comment advertises
 * the form. Ported as-is; see the test suite for the resulting behaviour.
 */
private fun parseTime(input: String): String? {
    val token = jsTrim(input).lowercase()

    val amPm = AM_PM_PATTERN.matchEntire(token)
    if (amPm != null) {
        var hour = amPm.groupValues[1].toInt()
        // Group 2 is the optional minutes; an unmatched group reads as "".
        val minute = amPm.groupValues[2].toIntOrNull() ?: 0
        val isPm = amPm.groupValues[3].replace(".", "") == "pm"

        if (isPm && hour < 12) hour += 12
        if (!isPm && hour == 12) hour = 0 // 12am -> 00:00

        // Only the upper bounds are checked: the pattern admits digits only, so
        // the C#'s `>= 0` halves can never fail.
        if (hour < 24 && minute < 60) return formatTime(hour, minute)

        // An am/pm token that names no real time is rejected outright, rather than
        // falling through to the 24-hour forms it cannot match anyway.
        return null
    }

    val hourMinute = HOUR_MINUTE_PATTERN.matchEntire(token)
    if (hourMinute != null) {
        val hour = hourMinute.groupValues[1].toInt()
        val minute = hourMinute.groupValues[2].toInt()
        // A token with a colon can never parse as a bare hour, so failing here is
        // the same as falling through to the C#'s `int.TryParse`.
        if (hour < 24 && minute < 60) return formatTime(hour, minute)
        return null
    }

    if (BARE_HOUR_PATTERN.matches(token)) {
        // Null for a number too large for an Int, which is no hour either.
        val hour = token.toIntOrNull()
        if (hour != null && hour in 0..23) return formatTime(hour, 0)
    }

    return null
}

/**
 * True when a token looks like schedule grammar — a keyword, day name, letter
 * combo, or time — rather than free text like a medication name.
 *
 * Used to split a command at the first grammar-like token, which is what keeps
 * a multi-word name intact.
 */
fun isGrammarToken(token: String): Boolean {
    if (jsTrim(token) == "") return false

    val normalized = token.lowercase()
    if (normalized in KEYWORDS) return true
    if (normalized in GRAMMAR_DAY_NAMES) return true
    if (parseLetterCombo(normalized) != null) return true
    return parseTime(normalized) != null
}

/**
 * Parse a schedule phrase into days and times.
 *
 * Fails in three ways, in this order: no input at all; at least one token that
 * could not be understood (reported individually, so the caller can quote
 * them); or an input that parsed cleanly but said nothing — `"at and"` is all
 * separators and no schedule.
 */
fun parseScheduleGrammar(input: String?): ScheduleParseResult {
    if (input == null || jsTrim(input) == "") {
        return failure("No schedule text provided.", emptyList())
    }

    val tokens = tokenize(input)

    val days = mutableSetOf<Int>()
    val times = mutableSetOf<String>()
    val unparseable = mutableListOf<String>()
    var daySpecSeen = false

    for (token in tokens) {
        when (token) {
            // These assert only that a day spec exists. Leaving the day set empty
            // is what makes `daysOfWeek` come out `null`, i.e. every day.
            "every", "daily", "day" -> {
                daySpecSeen = true
                continue
            }
            "at", "and" -> continue
            "weekdays" -> {
                daySpecSeen = true
                days.addAll(WEEKDAY_DAYS)
                continue
            }
            "weekends" -> {
                daySpecSeen = true
                days.addAll(WEEKEND_DAYS)
                continue
            }
        }

        val named = GRAMMAR_DAY_NAMES[token]
        if (named != null) {
            daySpecSeen = true
            days.add(named)
            continue
        }

        val time = parseTime(token)
        if (time != null) {
            times.add(time)
            continue
        }

        // Last chance: a run-together letter combo (mwf, tuth, mtwtf).
        val combo = parseLetterCombo(token)
        if (combo != null) {
            daySpecSeen = true
            days.addAll(combo)
            continue
        }

        unparseable.add(token)
    }

    if (unparseable.isNotEmpty()) {
        return failure(
            "Couldn't parse: ${unparseable.joinToString(", ")}. " +
                "Use a day spec (daily/weekdays/weekends/Mon…/MWF) and times like 08:00 or 8am.",
            unparseable,
        )
    }

    if (!daySpecSeen && times.isEmpty()) {
        return failure("Couldn't parse the schedule. Try 'daily at 08:00' or 'MWF at 09:00'.", tokens)
    }

    return ScheduleParseResult.Success(
        daysOfWeek = if (daySpecSeen && days.isNotEmpty()) days.sorted() else null,
        // Zero-padded, so string order is clock order.
        times = times.sorted(),
    )
}
