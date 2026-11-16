package com.taskrx.core.schedule

import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.asNeededTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.monthlyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/*
 * The backward-looking half of the schedule engine: "when did this last come due?".
 *
 * It exists so an overdue task can stay on the Tasks tab however old it is without
 * projecting months of history. The cases pin the one full cycle each variant searches,
 * the strict `before now` boundary, and the pause and move rules it inherits from
 * [nextOccurrences].
 */

private val UTC: ZoneId = ZoneOffset.UTC

private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC).toInstant().toEpochMilli()

private fun reminder(
    schedule: ScheduleTimes,
    pauses: List<PauseWindow> = emptyList(),
    overrides: List<OccurrenceOverride> = emptyList(),
): Reminder =
    createReminder(
        id = "task",
        userId = "user-1",
        kind = ReminderKind.Task,
        name = "A task",
        schedule = schedule,
        priority = Priority.Normal,
        createdAt = 0,
        updatedAt = 0,
        pauses = pauses,
        overrides = overrides,
    )

class LatestOccurrenceBeforeTest {
    @Test
    fun `a once task returns its moment while that moment is past`() {
        val at = utc(2026, 8, 1, 9)

        assertEquals(at, latestOccurrenceBefore(reminder(onceTimes(at)), utc(2026, 8, 6), UTC)?.fireAt)
    }

    @Test
    fun `a once task with a future moment has nothing behind it`() {
        val at = utc(2026, 8, 9, 9)

        assertNull(latestOccurrenceBefore(reminder(onceTimes(at)), utc(2026, 8, 6), UTC))
    }

    @Test
    fun `a moment exactly now is not before now`() {
        val at = utc(2026, 8, 6, 9)

        assertNull(latestOccurrenceBefore(reminder(onceTimes(at)), at, UTC))
    }

    @Test
    fun `daily returns the last time that has passed today`() {
        val r = reminder(dailyTimes(listOf("09:00", "21:00")))

        assertEquals(
            utc(2026, 8, 6, 9),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 12), UTC)?.fireAt,
        )
    }

    @Test
    fun `daily falls back to yesterday when today's times are still ahead`() {
        val r = reminder(dailyTimes(listOf("09:00", "21:00")))

        assertEquals(
            utc(2026, 8, 5, 21),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 8), UTC)?.fireAt,
        )
    }

    @Test
    fun `weekly reaches back to the last selected weekday`() {
        // Thursday 2026-08-06; the schedule is Monday only.
        val r = reminder(weeklyTimes(listOf(1), listOf("09:00")))

        assertEquals(
            utc(2026, 8, 3, 9),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 12), UTC)?.fireAt,
        )
    }

    @Test
    fun `weekly finds the latest moment even with several days and times`() {
        val r = reminder(weeklyTimes((0..6).toList(), listOf("09:00", "21:00")))

        assertEquals(
            utc(2026, 8, 6, 9),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 12), UTC)?.fireAt,
        )
    }

    @Test
    fun `monthly finds the latest moment even with several dates and times`() {
        val r = reminder(monthlyTimes(listOf(1, 5, 10, 15, 20, 25, 28), listOf("09:00", "21:00")))

        assertEquals(
            utc(2026, 8, 5, 21),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 12), UTC)?.fireAt,
        )
    }

    @Test
    fun `monthly reaches back to the last selected date`() {
        val r = reminder(monthlyTimes(listOf(15), listOf("09:00")))

        assertEquals(
            utc(2026, 7, 15, 9),
            latestOccurrenceBefore(r, utc(2026, 8, 6), UTC)?.fireAt,
        )
    }

    @Test
    fun `monthly clamps a 31st to the last day of a short month`() {
        val r = reminder(monthlyTimes(listOf(31), listOf("09:00")))

        assertEquals(
            utc(2026, 2, 28, 9),
            latestOccurrenceBefore(r, utc(2026, 3, 1), UTC)?.fireAt,
        )
    }

    @Test
    fun `interval returns the slot immediately before now`() {
        val start = utc(2026, 8, 1, 0)
        val r = reminder(intervalTimes(6, start))

        assertEquals(
            utc(2026, 8, 6, 6),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 8), UTC)?.fireAt,
        )
    }

    @Test
    fun `an interval of zero hours has no moments`() {
        assertNull(latestOccurrenceBefore(reminder(intervalTimes(0, utc(2026, 8, 1))), utc(2026, 8, 6), UTC))
    }

    @Test
    fun `a backlog and an as-needed medication have no moments`() {
        assertNull(latestOccurrenceBefore(reminder(noneTimes()), utc(2026, 8, 6), UTC))
        assertNull(latestOccurrenceBefore(reminder(asNeededTimes(240)), utc(2026, 8, 6), UTC))
    }

    @Test
    fun `the device zone is the default`() {
        val at = utc(2026, 8, 1, 9)

        assertEquals(at, latestOccurrenceBefore(reminder(onceTimes(at)), utc(2026, 8, 6))?.fireAt)
    }

    @Test
    fun `a paused moment is not a moment`() {
        val r =
            reminder(
                dailyTimes(listOf("09:00")),
                pauses = listOf(PauseWindow(utc(2026, 8, 6, 0), utc(2026, 8, 6, 12))),
            )

        assertEquals(
            utc(2026, 8, 5, 9),
            latestOccurrenceBefore(r, utc(2026, 8, 6, 10), UTC)?.fireAt,
        )
    }

    @Test
    fun `a pause longer than a cycle leaves nothing in range`() {
        val r =
            reminder(
                dailyTimes(listOf("09:00")),
                pauses = listOf(PauseWindow(utc(2026, 7, 1), utc(2026, 8, 6, 12))),
            )

        assertNull(latestOccurrenceBefore(r, utc(2026, 8, 6, 10), UTC))
    }

    @Test
    fun `a moved moment is found where it moved to`() {
        val from = utc(2026, 8, 3, 9)
        val to = utc(2026, 8, 4, 9)
        val r = reminder(onceTimes(from), overrides = listOf(OccurrenceOverride(from, to)))

        assertEquals(to, latestOccurrenceBefore(r, utc(2026, 8, 6), UTC)?.fireAt)
    }
}
