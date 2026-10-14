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
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/*
 * The editing helpers behind the schedule picker. Mirrors the non-visual half of
 * archive/expo-rn:apps/mobile/src/components/SchedulePicker.tsx.
 *
 * What makes a schedule SAVABLE is :core's `isWellFormedSchedule`; nothing here decides
 * that. These are presentation: where a form starts someone off, how a picked date and time
 * become an instant, and the hints shown beside a field.
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

/** Whether a stored time is the strict 24-hour HH:MM the schedule holds. */
fun isValidTimeInput(value: String): Boolean = TIME_PATTERN.matches(value)

/** The hour and minute a stored "HH:MM" names, or null when it is not one. */
fun timeInputToHourMinute(value: String): Pair<Int, Int>? =
    TIME_PATTERN.matchEntire(value)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

/** The stored "HH:MM" for a picked [hour] and [minute]: 24-hour and zero-padded, whatever the device shows. */
fun hourMinuteToTimeInput(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/** The hours field: a positive whole number, or 0 (which the schedule then refuses to save). */
fun parseIntervalHours(text: String): Int {
    // Leading digits, as JavaScript's parseInt read them.
    val parsed = Regex("""^\s*\d+""").find(text)?.value?.trim()?.toIntOrNull() ?: return 0
    return if (parsed > 0) parsed else 0
}

/**
 * The instant for a day the date picker returned and a time the time picker returned, in [zone].
 *
 * The date picker answers in UTC: [dateMillis] is midnight UTC of the calendar day the person
 * tapped. Reading that day in [zone] would name the day before anywhere behind UTC, so the day
 * is read in UTC and only then given the local time.
 */
fun pickedInstant(dateMillis: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long {
    val day = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return LocalDateTime.of(day, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
}

/** What to hand the date picker so it opens on the local day [ms] falls on: that day's midnight, in UTC. */
fun pickerDateMillis(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The local hour and minute of [ms] in [zone], for opening the time picker on a moment. */
fun instantHourMinute(ms: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
    val time = Instant.ofEpochMilli(ms).atZone(zone)
    return time.hour to time.minute
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
