package com.taskrx.core.parser

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.weeklyTimes

/*
 * The reminder parser's token patterns, ported from
 * `TaskRx.Core/ReminderParserPatterns.cs` in the retired .NET app.
 *
 * The C# uses `bool TryX(..., out T value, out int consumed)`. Here each
 * function returns `null` for "no match" and a [Match] (or a plain count, where
 * the C# only reports how many tokens were swallowed) otherwise, so a match
 * cannot be read without having been checked.
 *
 * Clock parsing is an explicit grammar. The C# first tries
 * `TimeOnly.TryParse` with the invariant culture and then four exact formats
 * (`h:mmtt`, `htt`, `h:mm`, `H:mm`). [parseClockTime] implements
 * `h[:mm[:ss]][am|pm]` directly rather than reproducing .NET's culture-driven
 * parser: `"8am"`, `"8:30pm"`, `"8:00"` and `"18:30"` all parse, a bare `"8"`
 * does not, and seconds are accepted but truncated because the domain keeps
 * minutes. A single-digit minute (`"8:5"`), which .NET's general parse may
 * accept, is not supported here.
 */

/**
 * ECMAScript `WhiteSpace` and `LineTerminator`, which is what JS `trim()` and the
 * regex `\s` strip. Kotlin's `trim()` disagrees at both ends: it keeps U+FEFF
 * and strips U+001C..U+001F, so a phrase of just `\uFEFF` would be blank in the
 * TypeScript and a name in Kotlin.
 */
private val JS_WHITESPACE: Set<Char> =
    "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF".toSet() + ('\u2000'..'\u200A')

/** JS `String.prototype.trim`. */
fun jsTrim(value: String): String = value.trim { it in JS_WHITESPACE }

/** A parsed value plus the number of tokens it swallowed (the C# `consumed`). */
data class Match<T>(val value: T, val consumed: Int)

/** `\d+(\.\d+)?\s*(mg|...)`, unanchored, as in the C# `DosageRegex`. */
private val DOSAGE_PATTERN =
    Regex("""\d+(\.\d+)?\s*(mg|mcg|g|ml|tablets?|capsules?|pills?|units?)""", RegexOption.IGNORE_CASE)

/** `h[:mm[:ss]][am|pm]`. Seconds are matched so they can be discarded. */
private val CLOCK_PATTERN = Regex("""(\d{1,2})(?::(\d{2}))?(?::\d{2})?(am|pm)?""", RegexOption.IGNORE_CASE)

/** Tokens can only be `[a-zA-Z0-9.:]+`, so no sign is ever present. */
private val INT_TOKEN_PATTERN = Regex("""\d+""")

/** `NumberStyles.Float` minus the signs the tokenizer cannot produce. */
private val FLOAT_TOKEN_PATTERN = Regex("""(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?""")

private val HOUR_UNITS: Set<String> = setOf("hour", "hours")
private val DAY_UNITS: Set<String> = setOf("day", "days")

/** `(first, second, value)` from `TryParseInstructions`. */
private val INSTRUCTION_PHRASES: List<Triple<String, String, String>> =
    listOf(
        Triple("with", "food", "with food"),
        Triple("with", "water", "with water"),
        Triple("without", "food", "without food"),
        Triple("after", "meals", "after meals"),
        Triple("before", "meals", "before meals"),
    )

/** Lookahead. `null` means the index is past the end of the input. */
fun tokenAt(tokens: List<String>, index: Int): String? = tokens.getOrNull(index)

/** `string.Equals(x, y, StringComparison.OrdinalIgnoreCase)`. */
fun equalsIgnoreCase(value: String?, lowercaseExpected: String): Boolean =
    value?.lowercase() == lowercaseExpected

/** `int.TryParse`, including the `Int32` range check. */
fun parseIntToken(token: String?): Int? {
    if (token == null || !INT_TOKEN_PATTERN.matches(token)) return null

    // `toIntOrNull` is null above Int.MAX_VALUE, which is the range check.
    return token.toIntOrNull()
}

/** `double.TryParse(..., NumberStyles.Float, InvariantCulture, ...)`. */
fun parseFloatToken(token: String): Double? =
    if (FLOAT_TOKEN_PATTERN.matches(token)) token.toDouble() else null

private fun pad(value: Int): String = value.toString().padStart(2, '0')

private fun parseClockTime(token: String): String? {
    val match = CLOCK_PATTERN.matchEntire(token) ?: return null

    var hour = match.groupValues[1].toInt()
    val minuteText = match.groups[2]?.value
    val designator = match.groups[3]?.value
    val minute = minuteText?.toInt() ?: 0
    if (minute > 59) return null

    if (designator == null) {
        // A lone number is a count, not a time: `TimeOnly.TryParse("8")` fails and
        // none of the four exact formats match it either.
        if (minuteText == null) return null
        if (hour > 23) return null
    } else {
        if (hour < 1 || hour > 12) return null

        val isAfternoon = designator.lowercase() == "pm"
        if (hour == 12) {
            hour = if (isAfternoon) 12 else 0
        } else if (isAfternoon) {
            hour += 12
        }
    }

    return "${pad(hour)}:${pad(minute)}"
}

fun looksLikeDosage(token: String): Boolean = DOSAGE_PATTERN.containsMatchIn(token)

fun hasDrugSuffix(tokens: List<String>): Boolean =
    tokens.any { token -> DRUG_SUFFIXES.any { suffix -> token.lowercase().endsWith(suffix) } }

/** The whole token becomes the dosage, verbatim, as in the C#. */
fun tryParseDosage(token: String): String? = if (looksLikeDosage(token)) token else null

fun tryParsePriority(token: String, tokens: List<String>, index: Int): Match<Priority>? {
    if (equalsIgnoreCase(token, "urgent")) return Match(Priority.Critical, 1)

    if (equalsIgnoreCase(token, "important")) return Match(Priority.High, 1)

    if (equalsIgnoreCase(token, "low") && equalsIgnoreCase(tokenAt(tokens, index + 1), "priority")) {
        return Match(Priority.Low, 2)
    }

    return null
}

/**
 * `every N hours`, `every N days`, `every <named time>`, `every <weekday>`.
 *
 * [now] replaces the C#'s `DateTimeOffset.UtcNow`, which is the parser's only
 * reach for ambient state; taking it as an argument is what keeps this pure.
 *
 * The weekday form consumes only `every` so the day itself is read by the main
 * loop, which is how a run of days accumulates.
 *
 * `every N days` is N*24 hours, capped at [Int.MAX_VALUE] because
 * `IntervalTimes.hours` is an `Int` here where the TypeScript original held an
 * unbounded double.
 */
fun tryParseEvery(token: String, tokens: List<String>, index: Int, now: Long): Match<ScheduleTimes>? {
    val next = tokenAt(tokens, index + 1)
    if (next == null || !equalsIgnoreCase(token, "every")) return null

    val count = parseIntToken(next)
    if (count != null) {
        val unit = tokenAt(tokens, index + 2)?.lowercase()
        if (unit != null && unit in HOUR_UNITS) {
            return Match(intervalTimes(count, now), 3)
        }
        if (unit != null && unit in DAY_UNITS) {
            val hours = minOf(count.toLong() * 24, Int.MAX_VALUE.toLong()).toInt()
            return Match(intervalTimes(hours, now), 3)
        }
        return null
    }

    val namedTime = lookupNamedTime(next)
    if (namedTime != null) {
        return Match(dailyTimes(listOf(namedTime)), 2)
    }

    if (lookupDayName(next) != null) {
        return Match(weeklyTimes(emptyList(), emptyList()), 1)
    }

    return null
}

/** `daily` on its own means 09:00. */
fun tryParseDaily(token: String): ScheduleTimes? =
    if (equalsIgnoreCase(token, "daily")) dailyTimes(listOf("09:00")) else null

/** `twice` means 08:00 and 18:00, whether or not `daily` follows. */
fun tryParseTwiceDaily(token: String): ScheduleTimes? =
    if (equalsIgnoreCase(token, "twice")) dailyTimes(listOf("08:00", "18:00")) else null

fun tryParseNamedTime(token: String): String? = lookupNamedTime(token)

/** A clock time, with or without a leading `at`. */
fun tryParseAtTime(token: String, tokens: List<String>, index: Int): Match<String>? {
    val leadingAt = equalsIgnoreCase(token, "at")
    val candidate = (if (leadingAt) tokenAt(tokens, index + 1) else token) ?: return null

    val time = parseClockTime(candidate) ?: return null

    return Match(time, if (leadingAt) 2 else 1)
}

fun tryParseDayName(token: String): Int? = lookupDayName(token)

fun isWeekly(token: String): Boolean = equalsIgnoreCase(token, "weekly")

fun tryParseInstructions(token: String, tokens: List<String>, index: Int): Match<String>? {
    val next = tokenAt(tokens, index + 1) ?: return null

    for ((first, second, value) in INSTRUCTION_PHRASES) {
        if (equalsIgnoreCase(token, first) && equalsIgnoreCase(next, second)) {
            return Match(value, 2)
        }
    }

    return null
}

/**
 * Inventory phrases — `N pills`, `N per dose`, `per dose`, `refill in N days`.
 *
 * Nothing is returned but a token count: the values themselves are read from
 * the whole token list by the extractors, so this only says how much of the
 * input the phrase covers. `N pills per dose` deliberately does not match, so
 * that `1 per dose` in `30 pills 1 per dose` is not misread as a pill count —
 * see [tryExtractPillsRemaining], which skips the same shape.
 */
fun tryParseRefill(token: String, tokens: List<String>, index: Int): Int? {
    val next = tokenAt(tokens, index + 1)

    if (parseIntToken(token) != null && next != null) {
        if (isDoseUnit(next)) {
            if (equalsIgnoreCase(tokenAt(tokens, index + 2), "per") &&
                equalsIgnoreCase(tokenAt(tokens, index + 3), "dose")
            ) {
                return null
            }
            return 2
        }

        if (equalsIgnoreCase(next, "per") && equalsIgnoreCase(tokenAt(tokens, index + 2), "dose")) {
            return 3
        }
    }

    if (equalsIgnoreCase(token, "per") && equalsIgnoreCase(next, "dose")) return 2

    if (equalsIgnoreCase(token, "refill") &&
        equalsIgnoreCase(next, "in") &&
        parseIntToken(tokenAt(tokens, index + 2)) != null
    ) {
        return if (equalsIgnoreCase(tokenAt(tokens, index + 3), "days")) 4 else 3
    }

    return null
}

/** `Times.Append(time).Distinct().OrderBy(t => t)`, on `"HH:MM"` strings. */
private fun mergeTimes(times: List<String>, time: String): List<String> =
    (times + time).distinct().sorted()

/**
 * Folds a time into whatever schedule has been built so far.
 *
 * An `interval` schedule is replaced by a fresh `daily` one rather than being
 * merged into, so `every 8 hours at 9am` ends up as a 09:00 daily dose. That is
 * what `ReminderParserPatterns.MergeTime` does — its two type tests fall
 * through to `new DailyTimes(...)` — and it looks like a bug, but the port keeps
 * it: changing it here would silently change what an existing phrase schedules.
 */
fun mergeTime(schedule: ScheduleTimes?, time: String): ScheduleTimes =
    when (schedule) {
        is DailyTimes -> dailyTimes(mergeTimes(schedule.times, time))
        is WeeklyTimes -> weeklyTimes(schedule.daysOfWeek, mergeTimes(schedule.times, time))
        else -> dailyTimes(listOf(time))
    }
