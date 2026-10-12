package com.taskrx.mobile.ui.forms

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/*
 * The editing helpers behind the schedule picker. Mirrors the non-visual half of
 * apps/mobile/src/components/SchedulePicker.tsx.
 *
 * What makes a schedule SAVABLE is :core's `isWellFormedSchedule`; nothing here decides
 * that. These are presentation: where a form starts someone off, how a typed instant is
 * read, and the hints shown beside a field.
 */

/** The five variants the picker edits, in the order a person is likely to want them. */
enum class ScheduleType(val label: String) {
    Daily("Daily"),
    Weekly("Weekly"),
    Interval("Interval"),
    Once("Once"),
    None("No time"),
}

fun scheduleTypeOf(schedule: ScheduleTimes): ScheduleType =
    when (schedule) {
        is DailyTimes -> ScheduleType.Daily
        is WeeklyTimes -> ScheduleType.Weekly
        is IntervalTimes -> ScheduleType.Interval
        is OnceTimes -> ScheduleType.Once
        NoneTimes -> ScheduleType.None
    }

/**
 * A fresh value of [type], used when the person switches variant. Switching replaces the
 * value rather than carrying fields across, because the variants share no field: an hours
 * count means nothing to a single moment.
 */
fun defaultSchedule(type: ScheduleType, now: Long): ScheduleTimes =
    when (type) {
        ScheduleType.Daily -> dailyTimes(listOf("09:00"))
        // Weekdays, which is what "weekly rather than daily" almost always means: the
        // person is excluding the weekend. Sunday is 0.
        ScheduleType.Weekly -> weeklyTimes(listOf(1, 2, 3, 4, 5), listOf("09:00"))
        ScheduleType.Interval -> intervalTimes(8, now)
        // An hour out, so the moment is ahead of the person filling the form in.
        ScheduleType.Once -> onceTimes(now + 3_600_000L)
        ScheduleType.None -> noneTimes()
    }

/** Sunday first; the index is the domain's weekday number. */
val WEEKDAY_LABELS: List<String> = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * [day] switched on or off, rebuilt from the canonical order rather than appended to, so
 * the stored order is Sunday-first however the person tapped them. Two schedules covering
 * the same days should not differ as JSON.
 */
fun toggleDay(days: List<Int>, day: Int): List<Int> {
    val selected = days.toMutableSet()
    if (!selected.add(day)) selected.remove(day)
    return WEEKDAY_LABELS.indices.filter { it in selected }
}

private val TIME_PATTERN = Regex("""^([01]\d|2[0-3]):([0-5]\d)$""")

/** Whether a typed time is the strict 24-hour HH:MM the hint asks for. */
fun isValidTimeInput(value: String): Boolean = TIME_PATTERN.matches(value)

/** The hours field: a positive whole number, or 0 (which the schedule then refuses to save). */
fun parseIntervalHours(text: String): Int {
    // Leading digits, as JavaScript's parseInt read them.
    val parsed = Regex("""^\s*\d+""").find(text)?.value?.trim()?.toIntOrNull() ?: return 0
    return if (parsed > 0) parsed else 0
}

private val INSTANT_INPUT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)

/** "YYYY-MM-DDTHH:MM" in [zone], the shape the instant fields are typed in. */
fun instantToInput(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    INSTANT_INPUT.format(Instant.ofEpochMilli(ms).atZone(zone))

/** The instant a typed local date-time names, or null while it is not yet one. */
fun inputToInstant(text: String, zone: ZoneId = ZoneId.systemDefault()): Long? =
    try {
        LocalDateTime.parse(text.trim(), INSTANT_INPUT).atZone(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    } catch (_: DateTimeException) {
        null
    }

/**
 * A count the person can clear. An empty box means "no value", which is a different thing
 * from zero: a supply of 0 pills is a claim, a blank field is the absence of one. Leading
 * numeric text is read the way JavaScript's `parseFloat` read it ("1." is 1, "7abc" is 7),
 * which is what lets "1.5" be typed one character at a time.
 */
fun parseCount(text: String): Double? {
    val match = Regex("""^\s*[+-]?(\d+\.?\d*|\.\d+)""").find(text) ?: return null
    return match.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
}
