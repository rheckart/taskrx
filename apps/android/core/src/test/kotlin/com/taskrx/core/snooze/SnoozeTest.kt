package com.taskrx.core.snooze

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UTC = ZoneId.of("UTC")
private val NEW_YORK = ZoneId.of("America/New_York")

private fun at(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

class SnoozeLengthTest {
    @Test
    fun `offers five lengths, shortest first, and the default is one of them`() {
        assertEquals(listOf(5, 10, 15, 30, 60), SNOOZE_CHOICES_MINUTES)
        assertEquals(15, DEFAULT_SNOOZE_MINUTES)
        assertTrue(DEFAULT_SNOOZE_MINUTES in SNOOZE_CHOICES_MINUTES)
    }

    @Test
    fun `keeps a stored length that is one of the choices`() {
        for (choice in SNOOZE_CHOICES_MINUTES) assertEquals(choice, snoozeMinutesOrDefault(choice))
    }

    @Test
    fun `falls back to the default when nothing is stored`() {
        assertEquals(15, snoozeMinutesOrDefault(null))
    }

    @Test
    fun `falls back to the default for a stored length that is not a choice`() {
        assertEquals(15, snoozeMinutesOrDefault(0))
        assertEquals(15, snoozeMinutesOrDefault(7))
        assertEquals(15, snoozeMinutesOrDefault(120))
        assertEquals(15, snoozeMinutesOrDefault(-5))
    }

    @Test
    fun `a snooze runs out that many minutes after it was taken`() {
        assertEquals(1_000L + 5 * 60_000L, snoozeUntil(1_000L, 5))
        assertEquals(1_000L + 60 * 60_000L, snoozeUntil(1_000L, 60))
    }
}

class RescheduleInstantTest {
    private val now = at(UTC, 2026, 8, 6, 9, 5, second = 30)

    @Test
    fun `puts a moment off to a clock time later today`() {
        assertEquals(at(UTC, 2026, 8, 6, 15, 30), rescheduleInstant(now, 15, 30, UTC))
    }

    @Test
    fun `accepts the last minute of the day`() {
        assertEquals(at(UTC, 2026, 8, 6, 23, 59), rescheduleInstant(now, 23, 59, UTC))
    }

    @Test
    fun `accepts the next minute, though it is under a minute away`() {
        assertEquals(at(UTC, 2026, 8, 6, 9, 6), rescheduleInstant(now, 9, 6, UTC))
    }

    @Test
    fun `refuses the minute already running, which is behind now by its seconds`() {
        assertNull(rescheduleInstant(now, 9, 5, UTC))
    }

    @Test
    fun `refuses a time that has passed, rather than reading it as tomorrow`() {
        assertNull(rescheduleInstant(now, 8, 0, UTC))
        assertNull(rescheduleInstant(now, 0, 0, UTC))
    }

    @Test
    fun `refuses a time equal to now, which would fire at once`() {
        val onTheMinute = at(UTC, 2026, 8, 6, 9, 5)
        assertNull(rescheduleInstant(onTheMinute, 9, 5, UTC))
    }

    @Test
    fun `refuses an hour or minute that is not on a clock`() {
        assertNull(rescheduleInstant(now, -1, 30, UTC))
        assertNull(rescheduleInstant(now, 24, 30, UTC))
        assertNull(rescheduleInstant(now, 12, -1, UTC))
        assertNull(rescheduleInstant(now, 12, 60, UTC))
    }

    @Test
    fun `reads today in the zone it is asked in, not in UTC`() {
        // 23:30 on 5 Aug in New York is already 6 Aug in UTC. Today is still the 5th there.
        val lateEvening = at(NEW_YORK, 2026, 8, 5, 23, 30)

        assertEquals(at(NEW_YORK, 2026, 8, 5, 23, 45), rescheduleInstant(lateEvening, 23, 45, NEW_YORK))
        assertNull(rescheduleInstant(lateEvening, 9, 0, NEW_YORK))
    }

    @Test
    fun `lands a time inside a skipped hour an hour later, as the zone says, rather than refusing it`() {
        // 8 Mar 2026: New York jumps from 02:00 to 03:00, so 02:30 does not exist.
        val earlyMorning = at(NEW_YORK, 2026, 3, 8, 1, 0)

        assertEquals(at(NEW_YORK, 2026, 3, 8, 3, 30), rescheduleInstant(earlyMorning, 2, 30, NEW_YORK))
    }

    @Test
    fun `uses the device zone when none is given`() {
        // The suite pins UTC (see LocalDayBoundsTest), so the default must agree with it.
        assertEquals(at(UTC, 2026, 8, 6, 15, 30), rescheduleInstant(now, 15, 30))
        assertTrue(canPickATimeToday(now))
    }
}

class CanPickATimeTodayTest {
    @Test
    fun `is true through the second-to-last minute of the day`() {
        assertTrue(canPickATimeToday(at(UTC, 2026, 8, 6, 23, 58, second = 59), UTC))
    }

    @Test
    fun `is false once even 23-59 is behind now`() {
        assertFalse(canPickATimeToday(at(UTC, 2026, 8, 6, 23, 59), UTC))
        assertFalse(canPickATimeToday(at(UTC, 2026, 8, 6, 23, 59, second = 30), UTC))
    }

    @Test
    fun `is true again after midnight`() {
        assertTrue(canPickATimeToday(at(UTC, 2026, 8, 7, 0, 0), UTC))
    }
}
