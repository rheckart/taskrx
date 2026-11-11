package com.taskrx.core.schedule

import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.DAYS_OF_MONTH
import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.MonthlyTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.isPausedAt
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The reminder schedule engine, ported from `ScheduleEngine.NextOccurrences`
 * in `TaskRx.Core/ScheduleEngine.cs` in the retired .NET app.
 *
 * It works on the unified `Reminder`, so `task` reminders schedule too, and it
 * handles `weekly`, plus the variants the union gained later — `monthly` among
 * them, which walks local days like `weekly` and clamps a 31st to a short
 * month's last day.
 *
 * Two behaviours differed from the predecessor engine, deliberately, and are
 * worth recording because they are the ones a reimplementation would get wrong:
 *
 * - **The `from` boundary.** An occurrence landing exactly on `from` is
 *   excluded for every variant, matching `while (next <= fromUtc)` and
 *   `if (utc <= fromUtc) continue;` in the C#. The boundary is exactly where
 *   an off-by-one produces a duplicate alarm as a dose comes due.
 * - **Calendar arithmetic.** Day-based schedules step local calendar days, not
 *   fixed 86_400_000 ms hops, which is what `DateOnly.FromDateTime(...)
 *   .AddDays(n)` plus `zone.GetUtcOffset(local)` does on the C# side. Across a
 *   daylight-saving transition a 09:00 dose stays at 09:00 instead of drifting
 *   an hour — the gap between doses is what changes, not the wall-clock time
 *   the user chose.
 *
 * Pure functions: no DB calls, no clock. The zone is a parameter that defaults
 * to the device's, so a test can pin one that has a clock change.
 */

private const val MS_PER_HOUR = 3_600_000L

/** `for (int dayOffset = 0; ...; dayOffset < 60; ...)` in `NextDailyOccurrences`. */
private const val DAILY_HORIZON_DAYS = 60

/** Eight weeks, matching the `dayOffset < 56` walk in `NextWeeklyOccurrences`. */
private const val WEEKLY_HORIZON_DAYS = 56

/**
 * A year of local days for `monthly`: enough for twelve occurrences of a
 * once-a-month schedule, and for the next one when a reminder is set for a date
 * the current month has already passed.
 *
 * 400 rather than 366 because `daily` already looks 60 days out and `weekly` 56,
 * and a monthly reminder is the variant the timeline asks furthest about: a day
 * either side of the horizon turns its last visible occurrence into a phantom
 * Missed under the sweep's seven-day lookback.
 */
private const val MONTHLY_HORIZON_DAYS = 400

/** The C# `count` default on `NextOccurrences`. */
private const val DEFAULT_COUNT = 64

private val TIME_OF_DAY = Regex("""([0-9]{1,2}):([0-9]{2})""")

/**
 * Parses `"HH:MM"` as a local wall-clock time.
 *
 * C# stores these as `TimeOnly`, which cannot hold an invalid time; the domain
 * type is a string, so malformed entries are dropped rather than trusted.
 */
private fun parseTimeOfDay(time: String): LocalTime? {
    val match = TIME_OF_DAY.matchEntire(time) ?: return null

    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null

    return LocalTime.of(hour, minute)
}

/** The parseable times, chronologically — the C#'s `Times.OrderBy(x => x)`. */
private fun sortedTimesOfDay(times: List<String>): List<LocalTime> =
    times.mapNotNull(::parseTimeOfDay).sorted()

/**
 * The instant of [time] on [day]. A wall-clock time inside a clock change that
 * does not exist is shifted forward by the length of the gap (02:30 becomes
 * 03:30); one that happens twice takes the first.
 */
private fun localFireAt(day: LocalDate, time: LocalTime, zone: ZoneId): Long =
    ZonedDateTime.of(day, time, zone).toInstant().toEpochMilli()

/** Day index, 0 = Sunday, matching C#'s `DayOfWeek` and the domain `Weekday`. */
private fun weekdayOf(day: LocalDate): Int = day.dayOfWeek.value % 7

/**
 * `$"{reminderId}:{fireAtUtc.ToUnixTimeMilliseconds()}"`.
 *
 * Stable for a given reminder and fire time, which is what lets the notification
 * scheduler re-run without duplicating alarms.
 */
private fun buildOccurrenceId(reminderId: String, fireAt: Long): String = "$reminderId:$fireAt"

private fun occurrenceAt(source: Reminder, fireAt: Long): ScheduledOccurrence =
    ScheduledOccurrence(
        occurrenceId = buildOccurrenceId(source.id, fireAt),
        reminderId = source.id,
        name = source.name,
        dosage = source.dosage,
        fireAt = fireAt,
    )

/**
 * The shared walk for the two day-based variants: local calendar days from the
 * one holding [fromMs], taking each of [times] on every day [onDay] admits,
 * until [count] are found or [horizonDays] are spent.
 */
private fun walkLocalDays(
    source: Reminder,
    times: List<LocalTime>,
    fromMs: Long,
    count: Int,
    zone: ZoneId,
    horizonDays: Int,
    onDay: (LocalDate) -> Boolean,
): List<ScheduledOccurrence> {
    val reference = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
    val results = mutableListOf<ScheduledOccurrence>()

    var dayOffset = 0
    while (results.size < count && dayOffset < horizonDays) {
        val day = reference.plusDays(dayOffset.toLong())
        if (onDay(day)) {
            for (time in times) {
                val fireAt = localFireAt(day, time, zone)
                if (fireAt <= fromMs) continue

                results.add(occurrenceAt(source, fireAt))
                if (results.size >= count) break
            }
        }
        dayOffset++
    }

    return results
}

private fun dailyOccurrences(
    source: Reminder,
    schedule: DailyTimes,
    fromMs: Long,
    count: Int,
    zone: ZoneId,
): List<ScheduledOccurrence> {
    val times = sortedTimesOfDay(schedule.times)
    if (times.isEmpty()) return emptyList()

    return walkLocalDays(source, times, fromMs, count, zone, DAILY_HORIZON_DAYS) { true }
}

/**
 * Interval schedules are absolute: they recur every N hours from `startAt`
 * regardless of the calendar, so no local-day walk and no horizon cap. The C#
 * fast-forwards one interval at a time; the arithmetic here lands on the same
 * slot without looping over a start instant far in the past.
 */
private fun intervalOccurrences(
    source: Reminder,
    schedule: IntervalTimes,
    fromMs: Long,
    count: Int,
): List<ScheduledOccurrence> {
    if (schedule.hours <= 0) return emptyList()

    val intervalMs = schedule.hours * MS_PER_HOUR
    var next = schedule.startAt
    if (next <= fromMs) {
        val steps = Math.floorDiv(fromMs - schedule.startAt, intervalMs) + 1
        next = schedule.startAt + steps * intervalMs
    }

    return List(count) { index -> occurrenceAt(source, next + index * intervalMs) }
}

/**
 * One moment and then nothing — the `once` variant.
 *
 * `count` never binds here: a single-moment schedule has exactly one occurrence
 * to give, so the caller's count is a ceiling it cannot reach. The `<= fromMs`
 * exclusion is the same boundary every other variant applies, which is what
 * keeps "the occurrence that has just come due is behind you" one rule rather
 * than four.
 */
private fun onceOccurrences(
    source: Reminder,
    schedule: OnceTimes,
    fromMs: Long,
): List<ScheduledOccurrence> {
    if (schedule.at <= fromMs) return emptyList()
    return listOf(occurrenceAt(source, schedule.at))
}

private fun weeklyOccurrences(
    source: Reminder,
    schedule: WeeklyTimes,
    fromMs: Long,
    count: Int,
    zone: ZoneId,
): List<ScheduledOccurrence> {
    val times = sortedTimesOfDay(schedule.times)
    if (schedule.daysOfWeek.isEmpty() || times.isEmpty()) return emptyList()

    return walkLocalDays(source, times, fromMs, count, zone, WEEKLY_HORIZON_DAYS) { day ->
        weekdayOf(day) in schedule.daysOfWeek
    }
}

/**
 * The date a `daysOfMonth` entry falls on in [month]: the day itself, or the
 * month's last day when the month is shorter.
 *
 * The clamp is the decision, not an accident of arithmetic: a reminder set for
 * the 31st fires on the 28th (the 29th in a leap year), the 30th, and so on, so
 * one is never silently skipped. Clamping the day also keeps two entries that
 * collide in a short month (`30` and `31` in February) on one date rather than
 * firing twice a few hours apart.
 */
private fun dayOfMonthIn(month: YearMonth, dayOfMonth: Int): LocalDate =
    month.atDay(minOf(dayOfMonth, month.lengthOfMonth()))

private fun monthlyOccurrences(
    source: Reminder,
    schedule: MonthlyTimes,
    fromMs: Long,
    count: Int,
    zone: ZoneId,
): List<ScheduledOccurrence> {
    val times = sortedTimesOfDay(schedule.times)
    val days = schedule.daysOfMonth.filter { it in DAYS_OF_MONTH }
    if (days.isEmpty() || times.isEmpty()) return emptyList()

    // The clamp happens once, here, so the shared walk only ever sees real
    // dates: asking whether *this* date is where any wanted date lands is the
    // one question that also folds a 30th and a 31st onto February's 28th.
    return walkLocalDays(source, times, fromMs, count, zone, MONTHLY_HORIZON_DAYS) { day ->
        val month = YearMonth.from(day)
        days.any { dayOfMonthIn(month, it) == day }
    }
}

/**
 * The next [count] occurrences of [reminder] strictly after [fromMs], in
 * chronological order.
 *
 * Returns fewer than [count] when the schedule cannot supply that many inside
 * its horizon: 60 local days for `daily`, 56 for `weekly`, 400 for `monthly`. `interval` has no
 * horizon and always returns exactly [count]. `once` returns at most one and
 * `none` always returns nothing, whatever [count] says.
 *
 * The `when` over [ScheduleTimes] is exhaustive, so the C#
 * `InvalidOperationException` for an unknown variant has no arm here: an
 * unknown variant is a compile error, and JSON that names one never gets past
 * `parseScheduleTimes`.
 *
 * @throws IllegalArgumentException when [count] is negative.
 */
fun nextOccurrences(
    reminder: Reminder,
    fromMs: Long,
    count: Int = DEFAULT_COUNT,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ScheduledOccurrence> {
    require(count >= 0) { "count must be a non-negative integer, received $count." }
    if (count == 0) return emptyList()
    if (reminder.pauses.isEmpty()) return scheduledOccurrences(reminder, fromMs, count, zone)

    return unpausedOccurrences(reminder, fromMs, count, zone)
}

/**
 * [nextOccurrences] for a Reminder with pauses: the same moments minus the paused ones, still
 * [count] of them when the Schedule has that many.
 *
 * Filtering a counted batch would hand back fewer than asked for, and a pause that spans the
 * whole batch would hand back none even though doses resume a week later. So each round asks the
 * Schedule for as many as are still missing, keeps the unpaused ones, and goes again from the
 * last moment seen. When the next moment would land inside a pause with an end, the walk jumps to
 * the end instead of stepping through it; one with no end leaves nothing more to find.
 */
private fun unpausedOccurrences(
    reminder: Reminder,
    fromMs: Long,
    count: Int,
    zone: ZoneId,
): List<ScheduledOccurrence> {
    val results = mutableListOf<ScheduledOccurrence>()
    var cursor = fromMs
    while (results.size < count) {
        // The earliest moment that could come next is one millisecond past the cursor.
        val covering = reminder.pauses.firstOrNull { isPausedAt(listOf(it), cursor + 1) }
        if (covering != null) {
            val until = covering.until ?: break
            cursor = until - 1
        }

        val batch = scheduledOccurrences(reminder, cursor, count - results.size, zone)
        // Nothing, not merely few: a short batch can be the Schedule's own look-ahead running
        // out inside a long pause, with doses still waiting beyond it.
        if (batch.isEmpty()) break
        results.addAll(batch.filterNot { isPausedAt(reminder.pauses, it.fireAt) })
        cursor = batch.last().fireAt
    }

    return results
}

private fun scheduledOccurrences(
    reminder: Reminder,
    fromMs: Long,
    count: Int,
    zone: ZoneId,
): List<ScheduledOccurrence> =
    when (val schedule = reminder.schedule) {
        is DailyTimes -> dailyOccurrences(reminder, schedule, fromMs, count, zone)
        is IntervalTimes -> intervalOccurrences(reminder, schedule, fromMs, count)
        is WeeklyTimes -> weeklyOccurrences(reminder, schedule, fromMs, count, zone)
        is MonthlyTimes -> monthlyOccurrences(reminder, schedule, fromMs, count, zone)
        is OnceTimes -> onceOccurrences(reminder, schedule, fromMs)
        // A Backlog reminder has no moments at all, which is the whole point of the
        // variant: it never reaches the timeline or the notification horizon, and is
        // acted on from the Backlog surface instead.
        NoneTimes -> emptyList()
        // Nor does a medication taken as needed: when the next dose is allowed is not a moment
        // that was due, so nothing about it can be missed (see `asneeded/AsNeeded.kt`).
        is AsNeededTimes -> emptyList()
    }
