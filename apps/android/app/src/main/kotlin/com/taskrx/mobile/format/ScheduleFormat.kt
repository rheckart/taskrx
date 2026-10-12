package com.taskrx.mobile.format

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
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
        is IntervalTimes -> {
            val start = Instant.ofEpochMilli(schedule.startAt).atZone(zone)
            "Every ${schedule.hours}h starting %02d:%02d".format(Locale.ROOT, start.hour, start.minute)
        }
        is OnceTimes -> "Once, ${formatDayTime(schedule.at, zone, locale)}"
        NoneTimes -> "In your backlog — no time set"
    }
