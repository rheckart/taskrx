package com.taskrx.core.schedule

import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HOUR = 3_600_000L
private val ZONE = ZoneOffset.UTC

private fun utc(day: Int, hour: Int = 0): Long =
    ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, ZONE).toInstant().toEpochMilli()

private fun med(schedule: ScheduleTimes, vararg pauses: PauseWindow): Reminder =
    createReminder(
        id = "med",
        userId = "u",
        kind = ReminderKind.Medication,
        name = "Warfarin",
        createdAt = 0,
        updatedAt = 0,
        schedule = schedule,
        pauses = pauses.toList(),
    )

private fun fireTimes(reminder: Reminder, from: Long, count: Int) =
    nextOccurrences(reminder, from, count, ZONE).map { it.fireAt }

private val DAILY_9 = dailyTimes(listOf("09:00"))

class OccurrencesPauseTest {
    @Test
    fun `a Reminder with no pauses is unchanged`() {
        assertEquals(listOf(utc(2, 9), utc(3, 9)), fireTimes(med(DAILY_9), utc(1, 12), 2))
    }

    @Test
    fun `moments inside a closed pause are skipped and the count is still filled`() {
        val reminder = med(DAILY_9, PauseWindow(utc(3), utc(6)))

        assertEquals(listOf(utc(2, 9), utc(6, 9), utc(7, 9)), fireTimes(reminder, utc(1, 12), 3))
    }

    @Test
    fun `the first moment of the pause is paused and the moment it ends is not`() {
        val atStart = med(DAILY_9, PauseWindow(utc(3, 9), utc(5, 9)))

        assertEquals(listOf(utc(2, 9), utc(5, 9)), fireTimes(atStart, utc(1, 12), 2))
    }

    @Test
    fun `an open pause leaves only the moments before it`() {
        val reminder = med(DAILY_9, PauseWindow(utc(3)))

        assertEquals(listOf(utc(2, 9)), fireTimes(reminder, utc(1, 12), 5))
    }

    @Test
    fun `asking from inside an open pause finds nothing`() {
        assertEquals(emptyList(), fireTimes(med(DAILY_9, PauseWindow(utc(1))), utc(4), 3))
    }

    @Test
    fun `asking from inside a closed pause starts at its end`() {
        val reminder = med(DAILY_9, PauseWindow(utc(1), utc(8)))

        assertEquals(listOf(utc(8, 9), utc(9, 9)), fireTimes(reminder, utc(4), 2))
    }

    @Test
    fun `a pause longer than the schedule's look-ahead still resumes after it`() {
        // 60 days is a daily schedule's reach; this pause runs 200.
        val reminder = med(DAILY_9, PauseWindow(utc(3), utc(3) + 200 * 24 * HOUR))

        assertEquals(listOf(utc(2, 9), utc(3) + 200 * 24 * HOUR + 9 * HOUR), fireTimes(reminder, utc(1, 12), 2))
    }

    @Test
    fun `a pause that starts after the first batch does not hide the moments before it`() {
        val reminder = med(DAILY_9, PauseWindow(utc(20), utc(22)))

        assertEquals(
            listOf(utc(18, 9), utc(19, 9), utc(22, 9), utc(23, 9)),
            fireTimes(reminder, utc(17, 12), 4),
        )
    }

    @Test
    fun `two pauses are both honoured`() {
        val reminder = med(DAILY_9, PauseWindow(utc(2), utc(3)), PauseWindow(utc(4), utc(5)))

        assertEquals(listOf(utc(1, 9), utc(3, 9), utc(5, 9)), fireTimes(reminder, utc(1), 3))
    }

    @Test
    fun `an interval schedule keeps its grid across a pause`() {
        val reminder = med(intervalTimes(8, utc(1)), PauseWindow(utc(2), utc(3, 4)))

        assertEquals(listOf(utc(1, 8), utc(1, 16), utc(3, 8)), fireTimes(reminder, utc(1), 3))
    }

    @Test
    fun `a once moment inside a pause is gone and one outside it stays`() {
        assertEquals(emptyList(), fireTimes(med(onceTimes(utc(3, 9)), PauseWindow(utc(3), utc(4))), utc(1), 3))
        assertEquals(listOf(utc(5, 9)), fireTimes(med(onceTimes(utc(5, 9)), PauseWindow(utc(3), utc(4))), utc(1), 3))
    }

    @Test
    fun `a schedule with no moments stays empty`() {
        assertEquals(emptyList(), fireTimes(med(noneTimes(), PauseWindow(utc(3), utc(4))), utc(1), 3))
    }

    @Test
    fun `a count of zero is still nothing`() {
        assertEquals(emptyList(), fireTimes(med(DAILY_9, PauseWindow(utc(3), utc(4))), utc(1), 0))
    }
}
