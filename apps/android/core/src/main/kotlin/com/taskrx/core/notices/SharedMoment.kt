package com.taskrx.core.notices

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/*
 * Reading a suggested date and time out of text the person explicitly shared or highlighted.
 *
 * Unlike `readNoticeDate` (which is deliberately restricted to absolute dates because it drives
 * automatic move proposals on existing commitments), a shared chat message commonly says
 * "tomorrow at 5pm", "Friday at 2:30pm", "tonight", or "at 4pm". Nothing is saved without the
 * person seeing and confirming the pre-filled picker first.
 */

/** A suggested moment read out of shared text, for pre-filling the picker. */
data class SharedMoment(
    /** Resolved epoch ms to pre-fill the "Once" schedule picker. */
    val at: Long,
    /** The words the suggestion came from (e.g. "tomorrow at 5pm", "Dec 4"). */
    val matchedText: String,
)

/**
 * Reads a suggested date and time from [text] as of [now] in [zone], or returns null when the
 * text names nothing clear (or names conflicting/ambiguous dates).
 *
 * Precedence:
 * 1. Explicit calendar date via [readNoticeDate] (e.g. `Dec 4`, `2026-12-10`, `18/11`).
 *    Ambiguous, competing, or impossible calendar dates return null rather than guessing.
 * 2. Relative day or weekday (`today`, `tonight`, `tomorrow`, `Friday`, `next Monday`),
 *    refusing when two different days are named.
 * 3. Explicit clock time alone (`at 4pm`, `14:30`, `noon`), landing on today if still ahead
 *    or tomorrow if that time has already passed.
 */
fun readSharedMoment(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): SharedMoment? {
    val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
    val today = nowZoned.toLocalDate()
    val clockTime = firstTimeMatch(text)

    when (val explicit = readNoticeDate(text, now, zone)) {
        is ReadDate.Found -> {
            val time = explicit.time ?: namedPartOfDay(text) ?: DEFAULT_NOTICE_TIME
            val at = resolveMoment(explicit.date, time, explicit.time != null || namedPartOfDay(text) != null, nowZoned, zone)
            val label = combineMatchedText(explicit.matchedText, clockTime?.text)
            return SharedMoment(at = at, matchedText = label)
        }
        is ReadDate.Unread -> {
            if (explicit.reason != UnreadReason.NoDate) return null
        }
    }

    val relative = relativeDayCandidates(text, today, clockTime?.time, now, zone)
    if (relative.isNotEmpty()) {
        val dates = relative.map { it.date }.distinct()
        if (dates.size > 1) return null
        val chosen = relative.first()
        val partTime = namedPartOfDay(text)
        val time = clockTime?.time ?: chosen.defaultTime ?: partTime ?: DEFAULT_NOTICE_TIME
        val hasSpecificTime = clockTime != null || chosen.defaultTime != null || partTime != null
        val at = resolveMoment(chosen.date, time, hasSpecificTime, nowZoned, zone)
        val label = combineMatchedText(chosen.text, clockTime?.text)
        return SharedMoment(at = at, matchedText = label)
    }

    if (clockTime != null) {
        val todayAt = ZonedDateTime.of(today, clockTime.time, zone).toInstant().toEpochMilli()
        val at =
            if (todayAt > now) {
                todayAt
            } else {
                ZonedDateTime.of(today.plusDays(1), clockTime.time, zone).toInstant().toEpochMilli()
            }
        return SharedMoment(at = at, matchedText = clockTime.text)
    }

    return null
}

private fun resolveMoment(
    date: LocalDate,
    time: LocalTime,
    hasSpecificTime: Boolean,
    nowZoned: ZonedDateTime,
    zone: ZoneId,
): Long {
    val instant = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    if (!hasSpecificTime && date == nowZoned.toLocalDate() && instant <= nowZoned.toInstant().toEpochMilli()) {
        val nextHour = nowZoned.plusHours(1).withMinute(0).withSecond(0).withNano(0)
        return if (nextHour.toLocalDate() == date) {
            nextHour.toInstant().toEpochMilli()
        } else {
            ZonedDateTime.of(date, LocalTime.of(23, 0), zone).toInstant().toEpochMilli()
        }
    }
    return instant
}

private fun combineMatchedText(dateText: String, timeText: String?): String =
    if (timeText == null) dateText else "$dateText at $timeText"

private data class RelativeDay(
    val date: LocalDate,
    val text: String,
    val defaultTime: LocalTime? = null,
)

private val WEEKDAYS: Map<String, DayOfWeek> =
    mapOf(
        "monday" to DayOfWeek.MONDAY,
        "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "thurs" to DayOfWeek.THURSDAY,
        "thur" to DayOfWeek.THURSDAY,
        "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

private val WEEKDAY_PATTERN: String =
    WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")

private val RELATIVE_DAY_REGEX =
    Regex(
        """\b(today|tonight|tomorrow|(?:(?:this|next)\s+)?(?:$WEEKDAY_PATTERN))\b""",
        RegexOption.IGNORE_CASE,
    )

private fun relativeDayCandidates(
    text: String,
    today: LocalDate,
    clockTime: LocalTime?,
    now: Long,
    zone: ZoneId,
): List<RelativeDay> {
    val results = ArrayList<RelativeDay>()
    for (match in RELATIVE_DAY_REGEX.findAll(text)) {
        val raw = match.value
        val lower = raw.lowercase(Locale.ROOT)
        val day =
            when {
                lower == "today" -> RelativeDay(today, raw)
                lower == "tonight" -> RelativeDay(today, raw, LocalTime.of(19, 0))
                lower == "tomorrow" -> RelativeDay(today.plusDays(1), raw)
                else -> {
                    val word = lower.substringAfterLast(' ')
                    val target = WEEKDAYS.getValue(word)
                    val offset = weekdayOffset(today, target, lower.startsWith("next "), clockTime, now, zone)
                    RelativeDay(today.plusDays(offset.toLong()), raw)
                }
            }
        results += day
    }
    return results
}

private fun weekdayOffset(
    today: LocalDate,
    target: DayOfWeek,
    isNext: Boolean,
    clockTime: LocalTime?,
    now: Long,
    zone: ZoneId,
): Int {
    val rawOffset = (target.value - today.dayOfWeek.value + 7) % 7
    if (rawOffset != 0) return rawOffset
    if (isNext || clockTime == null) return 7
    val todayAt = ZonedDateTime.of(today, clockTime, zone).toInstant().toEpochMilli()
    return if (todayAt > now) 0 else 7
}

private val PART_OF_DAY_REGEX = Regex("""\b(morning|afternoon|evening|tonight)\b""", RegexOption.IGNORE_CASE)

private fun namedPartOfDay(text: String): LocalTime? =
    when (PART_OF_DAY_REGEX.find(text)?.value?.lowercase(Locale.ROOT)) {
        "morning" -> LocalTime.of(9, 0)
        "afternoon" -> LocalTime.of(14, 0)
        "evening" -> LocalTime.of(18, 0)
        "tonight" -> LocalTime.of(19, 0)
        else -> null
    }
