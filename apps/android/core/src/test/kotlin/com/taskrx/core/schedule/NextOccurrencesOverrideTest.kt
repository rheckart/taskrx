package com.taskrx.core.schedule

import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

private fun at(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: ZoneId = ZoneOffset.UTC,
): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

private fun task(
    schedule: ScheduleTimes,
    overrides: List<OccurrenceOverride> = emptyList(),
    pauses: List<PauseWindow> = emptyList(),
): Reminder = createReminder(
    id = "task-1",
    userId = "user-1",
    kind = ReminderKind.Task,
    name = "Water the plants",
    schedule = schedule,
    createdAt = 0,
    updatedAt = 0,
    pauses = pauses,
    overrides = overrides,
)

private fun fireTimes(
    reminder: Reminder,
    fromMs: Long,
    count: Int,
    zone: ZoneId = ZoneOffset.UTC,
): List<Long> = nextOccurrences(reminder, fromMs, count, zone).map { it.fireAt }

/**
 * Moving one Occurrence, and what the Schedule does around it.
 *
 * Every case here is one the app gets wrong by applying the move in a caller instead of in
 * the engine: the horizon, the sweep, Catch up and the timeline all read `nextOccurrences`,
 * so the behaviours below are the ones every surface either shares or fights over.
 */
class NextOccurrencesOverrideTest {
    private val march1 = at(2026, 3, 1)

    @Test
    fun `a moved occurrence comes back at its new moment`() {
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))
        val times = fireTimes(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), march1, 3)

        assertEquals(
            listOf(at(2026, 3, 1, 9), at(2026, 3, 2, 11), at(2026, 3, 3, 9)),
            times,
        )
    }

    @Test
    fun `the occurrence carries the moment it moved to`() {
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))
        val occurrences = nextOccurrences(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), march1, 2)

        val second = occurrences[1]
        assertEquals("task-1:${at(2026, 3, 2, 11)}", second.occurrenceId)
        assertEquals("task-1", second.reminderId)
        assertEquals("Water the plants", second.name)
    }

    @Test
    fun `a source that has passed still moves its occurrence`() {
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))
        val times = fireTimes(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), at(2026, 3, 2, 10), 2)

        assertEquals(listOf(at(2026, 3, 2, 11), at(2026, 3, 3, 9)), times)
    }

    @Test
    fun `a target already behind us changes nothing`() {
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))
        val times = fireTimes(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), at(2026, 3, 2, 12), 2)

        assertEquals(listOf(at(2026, 3, 3, 9), at(2026, 3, 4, 9)), times)
    }

    @Test
    fun `two occurrences moved onto one moment produce one row`() {
        val overrides = listOf(
            OccurrenceOverride(at(2026, 3, 1, 9), at(2026, 3, 2, 11)),
            OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11)),
        )
        val times = fireTimes(task(dailyTimes(listOf("09:00")), overrides = overrides), march1, 3)

        assertEquals(listOf(at(2026, 3, 2, 11), at(2026, 3, 3, 9), at(2026, 3, 4, 9)), times)
    }

    @Test
    fun `a move that reorders the batch is sorted`() {
        val moved = OccurrenceOverride(at(2026, 3, 1, 9), at(2026, 3, 3, 8))
        val times = fireTimes(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), march1, 3)

        assertEquals(
            listOf(at(2026, 3, 2, 9), at(2026, 3, 3, 8), at(2026, 3, 3, 9)),
            times,
        )
    }

    @Test
    fun `a source the schedule no longer produces is inert`() {
        // Weekly on Mondays; the override names a Tuesday, as it would after an edit from
        // Tuesdays to Mondays.
        val orphan = OccurrenceOverride(at(2026, 3, 3, 9), at(2026, 3, 3, 10))
        val times = fireTimes(task(weeklyTimes(listOf(1), listOf("09:00")), overrides = listOf(orphan)), march1, 2)

        assertEquals(listOf(at(2026, 3, 2, 9), at(2026, 3, 9, 9)), times)
    }

    @Test
    fun `a live move and a dead one on one reminder`() {
        val overrides = listOf(
            OccurrenceOverride(at(2026, 3, 3, 9), at(2026, 3, 3, 10)),
            OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11)),
        )
        val times = fireTimes(
            task(weeklyTimes(listOf(1), listOf("09:00")), overrides = overrides),
            march1,
            2,
        )

        assertEquals(listOf(at(2026, 3, 2, 11), at(2026, 3, 9, 9)), times)
    }

    @Test
    fun `a pause over the source keeps the occurrence where it was`() {
        val paused = PauseWindow(at(2026, 3, 2, 8), at(2026, 3, 2, 10))
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))
        val times = fireTimes(
            task(dailyTimes(listOf("09:00")), overrides = listOf(moved), pauses = listOf(paused)),
            march1,
            2,
        )

        assertEquals(listOf(at(2026, 3, 1, 9), at(2026, 3, 3, 9)), times)
    }

    @Test
    fun `a pause over the target leaves the occurrence at its own moment`() {
        val paused = PauseWindow(at(2026, 3, 2, 10), at(2026, 3, 2, 12))
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))
        val times = fireTimes(
            task(dailyTimes(listOf("09:00")), overrides = listOf(moved), pauses = listOf(paused)),
            march1,
            3,
        )

        assertEquals(listOf(at(2026, 3, 1, 9), at(2026, 3, 2, 9), at(2026, 3, 3, 9)), times)
    }

    @Test
    fun `a once reminder moves, before and after its source`() {
        val reminder = task(onceTimes(at(2026, 3, 10, 9)), overrides = listOf(OccurrenceOverride(at(2026, 3, 10, 9), at(2026, 3, 12, 14))))

        assertEquals(listOf(at(2026, 3, 12, 14)), fireTimes(reminder, march1, 1))
        assertEquals(listOf(at(2026, 3, 12, 14)), fireTimes(reminder, at(2026, 3, 11), 1))
        // Past the target too, it is simply gone.
        assertEquals(emptyList(), fireTimes(reminder, at(2026, 3, 13), 1))
    }

    @Test
    fun `an interval reminder moves, and only from a real slot`() {
        val schedule = intervalTimes(24, at(2026, 3, 1, 9))
        val real = OccurrenceOverride(at(2026, 3, 3, 9), at(2026, 3, 3, 10))
        val invented = OccurrenceOverride(at(2026, 3, 3, 11), at(2026, 3, 3, 12))

        assertEquals(
            listOf(at(2026, 3, 1, 9), at(2026, 3, 2, 9), at(2026, 3, 3, 10)),
            fireTimes(task(schedule, overrides = listOf(real)), march1, 3),
        )
        assertEquals(
            listOf(at(2026, 3, 1, 9), at(2026, 3, 2, 9), at(2026, 3, 3, 9)),
            fireTimes(task(schedule, overrides = listOf(invented)), march1, 3),
        )
    }

    @Test
    fun `a schedule with no moments ignores its moves`() {
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))

        assertEquals(emptyList(), fireTimes(task(noneTimes(), overrides = listOf(moved)), march1, 3))
    }

    @Test
    fun `a move across a clock change keeps both wall-clock times`() {
        val reminder = task(
            dailyTimes(listOf("09:00")),
            overrides = listOf(OccurrenceOverride(at(2026, 3, 7, 9, zone = NEW_YORK), at(2026, 3, 10, 9, zone = NEW_YORK))),
        )
        val times = fireTimes(reminder, at(2026, 3, 6, zone = NEW_YORK), 3, NEW_YORK)

        assertEquals(
            listOf(
                at(2026, 3, 6, 9, zone = NEW_YORK),
                at(2026, 3, 8, 9, zone = NEW_YORK),
                at(2026, 3, 9, 9, zone = NEW_YORK),
            ),
            times,
        )
        // The batch really does cross the spring-forward change: same wall-clock hour, one
        // hour apart in absolute terms.
        val offsets = times.map { Instant.ofEpochMilli(it).atZone(NEW_YORK).offset.totalSeconds }
        assertEquals(listOf(-5 * 3600), offsets.take(1))
        assertEquals(listOf(-4 * 3600), offsets.drop(1).take(1))
    }

    @Test
    fun `the answer stays as long as asked for`() {
        val reminder = task(
            dailyTimes(listOf("09:00")),
            overrides = listOf(OccurrenceOverride(at(2026, 3, 1, 9), at(2026, 3, 1, 10))),
        )

        assertEquals(3, fireTimes(reminder, march1, 3).size)
        assertEquals(1, fireTimes(reminder, march1, 1).size)
    }

    @Test
    fun `a count of zero asks for nothing, with a move or without`() {
        val moved = OccurrenceOverride(at(2026, 3, 2, 9), at(2026, 3, 2, 11))

        assertEquals(emptyList(), fireTimes(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), march1, 0))
        assertFailsWith<IllegalArgumentException> {
            nextOccurrences(task(dailyTimes(listOf("09:00")), overrides = listOf(moved)), march1, -1)
        }
    }

    @Test
    fun `an unmoved reminder is untouched by an empty list`() {
        val plain = fireTimes(task(dailyTimes(listOf("09:00"))), march1, 3)
        val explicit = fireTimes(task(dailyTimes(listOf("09:00")), overrides = emptyList()), march1, 3)

        assertEquals(plain, explicit)
        assertTrue(plain.isNotEmpty())
    }
}
