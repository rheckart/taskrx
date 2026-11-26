package com.taskrx.core.notices

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/*
 * Reading a date out of a Notice's kept sentence.
 *
 * The rule is deliberately narrow (D6, D7 in `docs/plans/external-notices.md`): absolute
 * dates only, never a relative word, never a guessed year, and never a numeric date whose
 * month and day could be either way round. A wrong date on a commitment is the failure this
 * app exists to avoid, so a sentence that does not say plainly when it means yields no date
 * and the screen says so.
 *
 * This is the only date parser in the product. The server keeps sentence spans and does no
 * parsing, so the two cannot drift.
 */

/** The time a notice's date takes when the notice names none: the parser's default. */
val DEFAULT_NOTICE_TIME: LocalTime = LocalTime.of(9, 0)

/** Why a sentence yielded no date. Each is a different sentence to show the person. */
enum class UnreadReason {
    /** No date-shaped text at all. */
    NoDate,

    /** A numeric date whose month and day could be either way round (`05/06`). */
    Ambiguous,

    /** Two different dates, so there is no one date to propose. */
    Competing,

    /** A date-shaped text that is not a real calendar date (`Feb 30`). */
    Impossible,
}

/**
 * What a sentence said about when, or why it said nothing usable.
 *
 * [Found.time] is null when the sentence named a date but no time; the caller then keeps the
 * occurrence's own wall-clock time. [Found.matchedText] is the date expression itself, so the
 * screen can mark the words the reading came from.
 */
sealed interface ReadDate {
    data class Found(
        val date: LocalDate,
        val time: LocalTime?,
        val matchedText: String,
    ) : ReadDate {
        /** The instant this reading names, using [fallback] when the sentence named no time. */
        fun moment(zone: ZoneId, fallback: LocalTime = DEFAULT_NOTICE_TIME): Long =
            ZonedDateTime.of(date, time ?: fallback, zone).toInstant().toEpochMilli()
    }

    data class Unread(val reason: UnreadReason) : ReadDate
}

/**
 * Reads the one date [text] names, as of [now] in [zone].
 *
 * A date with no year is the next such date at or after today, so "Nov 18" read on 14
 * November is this year and read on 20 November is next year. A date with a year is taken as
 * written. Two different dates in one sentence are refused rather than ranked.
 */
fun readNoticeDate(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReadDate {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val candidates = dateCandidates(text, today)
    if (candidates.isEmpty()) return ReadDate.Unread(UnreadReason.NoDate)

    val valid = candidates.filterIsInstance<Candidate.Valid>()
    if (valid.isEmpty()) {
        val reason =
            when {
                candidates.any { it is Candidate.Ambiguous } -> UnreadReason.Ambiguous
                else -> UnreadReason.Impossible
            }
        return ReadDate.Unread(reason)
    }

    val dates = valid.map { it.date }.distinct()
    if (dates.size > 1) return ReadDate.Unread(UnreadReason.Competing)

    return ReadDate.Found(dates.single(), firstTime(text), valid.first().text)
}

/* ---------------------------------------------------------------------------------------
 * Dates
 * --------------------------------------------------------------------------------------- */

private sealed interface Candidate {
    val start: Int
    val end: Int

    data class Valid(val date: LocalDate, val text: String, override val start: Int, override val end: Int) : Candidate

    data class Ambiguous(override val start: Int, override val end: Int) : Candidate

    data class Invalid(override val start: Int, override val end: Int) : Candidate
}

private val MONTH_NAMES: Map<String, Month> =
    buildMap {
        for (month in Month.entries) {
            val name = month.name.lowercase(Locale.ROOT)
            put(name, month)
            put(name.take(3), month)
        }
        // The one abbreviation people write that is not the first three letters.
        put("sept", Month.SEPTEMBER)
    }

private val MONTH_PATTERN: String =
    MONTH_NAMES.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

private val ISO_DATE = Regex("""(?<!\d)(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")

private val MONTH_FIRST =
    Regex(
        """\b($MONTH_PATTERN)\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

private val DAY_FIRST =
    Regex(
        """(?<!\d)(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($MONTH_PATTERN)(?:,?\s+(\d{4}))?(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

/** A numeric date with a year is not one of the accepted forms; it is refused, not guessed. */
private val NUMERIC_WITH_YEAR = Regex("""(?<!\d)(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2,4})(?!\d)""")

private val NUMERIC_DATE = Regex("""(?<!\d)(\d{1,2})[/.\-](\d{1,2})(?!\d)""")

private fun dateCandidates(text: String, today: LocalDate): List<Candidate> {
    val all = ArrayList<Candidate>()

    for (match in ISO_DATE.findAll(text)) {
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val date = calendarDate(year, month, day)
        all +=
            if (date == null) {
                Candidate.Invalid(match.range.first, match.range.last + 1)
            } else {
                Candidate.Valid(date, match.value, match.range.first, match.range.last + 1)
            }
    }

    for (match in MONTH_FIRST.findAll(text)) {
        val month = monthOf(match.groupValues[1]) ?: continue
        all += resolve(month, match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull(), today, match)
    }

    for (match in DAY_FIRST.findAll(text)) {
        val month = monthOf(match.groupValues[2]) ?: continue
        all += resolve(month, match.groupValues[1].toInt(), match.groupValues[3].toIntOrNull(), today, match)
    }

    for (match in NUMERIC_WITH_YEAR.findAll(text)) {
        all += numericWithYearCandidate(match)
    }

    for (match in NUMERIC_DATE.findAll(text)) {
        all += numericCandidate(match, today)
    }

    // Earliest first, and the longer expression when two start together, so an ISO date wins
    // over the `11-18` inside it. Anything overlapping what was already kept is dropped.
    val kept = ArrayList<Candidate>()
    for (candidate in all.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))) {
        if (kept.none { candidate.start < it.end && it.start < candidate.end }) kept += candidate
    }
    return kept
}

private fun monthOf(name: String): Month? = MONTH_NAMES[name.lowercase(Locale.ROOT)]

private fun calendarDate(year: Int, month: Int, day: Int): LocalDate? =
    try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

/**
 * A month-name date, with or without a year. Without one it is the next such date at or after
 * [today]; the walk over a few years is what lets "Feb 29" find the next leap year instead of
 * being called impossible.
 */
private fun resolve(month: Month, day: Int, year: Int?, today: LocalDate, match: MatchResult): Candidate {
    val start = match.range.first
    val end = match.range.last + 1
    if (year != null) {
        val date = calendarDate(year, month.value, day) ?: return Candidate.Invalid(start, end)
        return Candidate.Valid(date, match.value, start, end)
    }

    for (offset in 0..8) {
        val date = calendarDate(today.year + offset, month.value, day) ?: continue
        if (!date.isBefore(today)) return Candidate.Valid(date, match.value, start, end)
    }
    return Candidate.Invalid(start, end)
}

/**
 * A two-component numeric date. One component must exceed 12, which is the only way to know
 * which is the month; when neither does, the date is refused rather than guessed (D7).
 */
private fun numericCandidate(match: MatchResult, today: LocalDate): Candidate {
    val start = match.range.first
    val end = match.range.last + 1
    val first = match.groupValues[1].toInt()
    val second = match.groupValues[2].toInt()

    if (first > 12 && second > 12) return Candidate.Invalid(start, end)
    val (month, day) =
        when {
            first > 12 -> second to first
            second > 12 -> first to second
            else -> return Candidate.Ambiguous(start, end)
        }
    if (month !in 1..12 || day !in 1..31) return Candidate.Invalid(start, end)

    val date = calendarDate(today.year, month, day) ?: return Candidate.Invalid(start, end)
    val resolved = if (date.isBefore(today)) calendarDate(today.year + 1, month, day) ?: return Candidate.Invalid(start, end) else date
    return Candidate.Valid(resolved, match.value, start, end)
}

/**
 * A numeric date with a year (`18/11/2026`). The year is written out, so nothing is guessed but
 * which part is the month, and that is read by the rule [numericCandidate] uses: one part must
 * exceed 12 (D7). A two-digit year is refused as well, since its century would be a guess (D6).
 */
private fun numericWithYearCandidate(match: MatchResult): Candidate {
    val start = match.range.first
    val end = match.range.last + 1
    val first = match.groupValues[1].toInt()
    val second = match.groupValues[2].toInt()
    val yearText = match.groupValues[3]
    if (yearText.length != 4) return Candidate.Ambiguous(start, end)

    val (month, day) =
        when {
            first > 12 && second > 12 -> return Candidate.Invalid(start, end)
            first > 12 -> second to first
            second > 12 -> first to second
            else -> return Candidate.Ambiguous(start, end)
        }
    val date = calendarDate(yearText.toInt(), month, day) ?: return Candidate.Invalid(start, end)
    return Candidate.Valid(date, match.value, start, end)
}

/* ---------------------------------------------------------------------------------------
 * Times
 * --------------------------------------------------------------------------------------- */

internal data class TimeMatch(val time: LocalTime, val text: String)

private data class TimeCandidate(val start: Int, val end: Int, val time: LocalTime, val text: String)

private val CLOCK_TIME = Regex("""(?<!\d)(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", RegexOption.IGNORE_CASE)

private val PLAIN_TIME = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?!\d)""")

private val NOON = Regex("""\bnoon\b""", RegexOption.IGNORE_CASE)

private val MIDNIGHT = Regex("""\bmidnight\b""", RegexOption.IGNORE_CASE)

/** The first time the sentence names, or null when it names none. */
private fun firstTime(text: String): LocalTime? = firstTimeMatch(text)?.time

/** The first clock time the text names, together with the matched words, or null when none. */
internal fun firstTimeMatch(text: String): TimeMatch? {
    val all = ArrayList<TimeCandidate>()

    for (match in CLOCK_TIME.findAll(text)) {
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        if (hour !in 1..12 || minute !in 0..59) continue
        val meridiem = match.groupValues[3].lowercase(Locale.ROOT)
        val hour24 =
            when {
                meridiem == "am" && hour == 12 -> 0
                meridiem == "pm" && hour != 12 -> hour + 12
                else -> hour
            }
        all += TimeCandidate(match.range.first, match.range.last + 1, LocalTime.of(hour24, minute), match.value)
    }

    for (match in PLAIN_TIME.findAll(text)) {
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) continue
        all += TimeCandidate(match.range.first, match.range.last + 1, LocalTime.of(hour, minute), match.value)
    }

    for (match in NOON.findAll(text)) {
        all += TimeCandidate(match.range.first, match.range.last + 1, LocalTime.NOON, match.value)
    }

    for (match in MIDNIGHT.findAll(text)) {
        all += TimeCandidate(match.range.first, match.range.last + 1, LocalTime.MIDNIGHT, match.value)
    }

    val kept = ArrayList<TimeCandidate>()
    for (candidate in all.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))) {
        if (kept.none { candidate.start < it.end && it.start < candidate.end }) kept += candidate
    }
    return kept.firstOrNull()?.let { TimeMatch(it.time, it.text) }
}
