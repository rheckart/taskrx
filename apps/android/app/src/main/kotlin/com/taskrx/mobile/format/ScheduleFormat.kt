package com.taskrx.mobile.format

import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.MonthlyTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

/**
 * How this app says a Schedule out loud. Mirrors archive/expo-rn:apps/mobile/src/format/schedule.ts.
 *
 * It is the sentence natural-language entry shows back before saving, so "what the
 * parser understood" is only as reviewable as these strings are true. A schedule that
 * renders as though it were set when it is not (an empty `daily`, a `weekly` with no
 * days) invites someone to accept a reminder that will never come due.
 */
fun describeSchedule(
    schedule: ScheduleTimes,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    when (schedule) {
        is DailyTimes ->
            if (schedule.times.isEmpty()) "Daily (no times set)" else "Daily at ${schedule.times.joinToString(", ")}"
        is WeeklyTimes ->
            if (schedule.daysOfWeek.isEmpty() || schedule.times.isEmpty()) {
                "Weekly (not fully set)"
            } else {
                "${schedule.daysOfWeek.joinToString(", ") { DAY_NAMES[it] }} at ${schedule.times.joinToString(", ")}"
            }
        is MonthlyTimes ->
            if (schedule.daysOfMonth.isEmpty() || schedule.times.isEmpty()) {
                "Monthly (not fully set)"
            } else {
                "${schedule.daysOfMonth.joinToString(", ") { ordinalOf(it) }} of the month at ${schedule.times.joinToString(", ")}"
            }
        is IntervalTimes -> {
            val start = Instant.ofEpochMilli(schedule.startAt).atZone(zone)
            "Every ${describeInterval(schedule.hours)} starting %02d:%02d".format(Locale.ROOT, start.hour, start.minute)
        }
        is OnceTimes -> "Once, ${formatDayTime(schedule.at, zone, locale)}"
        NoneTimes -> "In your backlog — no time set"
        is AsNeededTimes -> describeAsNeeded(schedule)
    }

/**
 * "1st", "2nd", "3rd", "21st", "11th", "31st": the way a date of the month is said
 * out loud, which the plain number is not ("31 of the month").
 *
 * The teens are the exception the suffix rule has: 11, 12 and 13 end in 1, 2 and
 * 3 without being first, second or third.
 */
fun ordinalOf(day: Int): String {
    val suffix =
        when {
            day % 100 in 11..13 -> "th"
            day % 10 == 1 -> "st"
            day % 10 == 2 -> "nd"
            day % 10 == 3 -> "rd"
            else -> "th"
        }
    return "$day$suffix"
}

/** "As needed · at least 4 hours apart · up to 5 a day": the rule, since there are no times to read out. */
fun describeAsNeeded(schedule: AsNeededTimes): String {
    val gap = "at least ${describeMinutes(schedule.minGapMinutes)} apart"
    return "As needed · $gap" + (schedule.maxPer24h?.let { " · up to $it in 24 hours" } ?: "")
}

/** "4 hours", "90 minutes", "1 hour 30 minutes". */
fun describeMinutes(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    fun unit(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"
    return when {
        hours == 0 -> unit(rest, "minute")
        rest == 0 -> unit(hours, "hour")
        else -> "${unit(hours, "hour")} ${unit(rest, "minute")}"
    }
}

/** An interval in the largest unit that counts it exactly: "2 weeks", "3 days", "1 hour", "36 hours". */
fun describeInterval(hours: Int): String {
    val (count, noun) =
        when {
            hours > 0 && hours % 168 == 0 -> hours / 168 to "week"
            hours > 0 && hours % 24 == 0 -> hours / 24 to "day"
            else -> hours to "hour"
        }
    return "$count $noun${if (count == 1) "" else "s"}"
}
