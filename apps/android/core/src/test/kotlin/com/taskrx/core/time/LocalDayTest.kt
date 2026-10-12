package com.taskrx.core.time

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MS_PER_HOUR = 3_600_000L

/** Local wall-clock helper, in the zone the suite runs at (`UTC`) unless told otherwise. */
private fun local(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

class LocalDayBoundsTest {
    @Test
    fun `runs at the zone the vitest suite pins`() {
        assertEquals(ZoneId.of("UTC"), ZoneId.systemDefault())
    }

    @Test
    fun `spans the day holding now, midnight to its last millisecond`() {
        val bounds = localDayBounds(local(2026, 8, 13, 9, 30), 0)

        assertEquals(local(2026, 8, 13), bounds.start)
        assertEquals(local(2026, 8, 13, 23, 59) + 59_999, bounds.end)
    }

    @Test
    fun `reports the same day whatever time of day it is asked`() {
        assertEquals(
            localDayBounds(local(2026, 8, 13, 0, 0), 0),
            localDayBounds(local(2026, 8, 13, 23, 59), 0),
        )
    }

    @Test
    fun `counts backwards for a negative offset`() {
        val bounds = localDayBounds(local(2026, 8, 13, 9), -1)

        assertEquals(local(2026, 8, 12), bounds.start)
        assertEquals(local(2026, 8, 12, 23, 59) + 59_999, bounds.end)
    }

    @Test
    fun `counts forwards for a positive offset`() {
        assertEquals(local(2026, 8, 14), localDayBounds(local(2026, 8, 13, 9), 1).start)
    }

    @Test
    fun `rolls back over a month boundary`() {
        assertEquals(local(2026, 2, 28), localDayBounds(local(2026, 3, 1, 9), -1).start)
    }

    @Test
    fun `rolls forward over a year boundary`() {
        assertEquals(local(2026, 1, 1), localDayBounds(local(2025, 12, 31, 9), 1).start)
    }

    @Test
    fun `reaches a whole week back in one step`() {
        assertEquals(local(2026, 8, 7), localDayBounds(local(2026, 8, 13, 9), -6).start)
    }
}

class LocalCalendarDaysBetweenTest {
    @Test
    fun `is zero for two moments on the same day`() {
        assertEquals(
            0,
            localCalendarDaysBetween(local(2026, 8, 13, 0, 1), local(2026, 8, 13, 23)),
        )
    }

    @Test
    fun `counts the days between, not the hours`() {
        // Twenty-five minutes apart, and still a day: the answer is which day the
        // second moment lands on, not how much of the first one was left.
        assertEquals(
            1,
            localCalendarDaysBetween(local(2026, 8, 13, 23, 50), local(2026, 8, 14, 0, 15)),
        )
    }

    @Test
    fun `counts a whole week`() {
        assertEquals(7, localCalendarDaysBetween(local(2026, 8, 13, 9), local(2026, 8, 20, 18)))
    }

    @Test
    fun `goes negative when the second moment is on an earlier day`() {
        assertEquals(-2, localCalendarDaysBetween(local(2026, 8, 13, 9), local(2026, 8, 11, 9)))
    }

    @Test
    fun `counts across a month boundary`() {
        assertEquals(5, localCalendarDaysBetween(local(2026, 2, 26, 9), local(2026, 3, 3, 9)))
    }

    @Test
    fun `counts across a year boundary`() {
        assertEquals(3, localCalendarDaysBetween(local(2025, 12, 30, 9), local(2026, 1, 2, 9)))
    }
}

/**
 * The reason this is one function rather than the same lines in a screen and in
 * the adherence period.
 *
 * A local day is not 24 hours twice a year, and both callers care: the timeline
 * would drop an 23:00 row or double an 00:30 one, and the adherence period would
 * start an hour off midnight and clip the first day of its own window. Neither
 * bug is visible in the `UTC` the rest of the suite runs at, which is why the
 * span is asserted here explicitly, in `America/New_York`, rather than left to
 * the callers' own cases.
 */
class LocalDayBoundsDaylightSavingTest {
    private val newYork = ZoneId.of("America/New_York")

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(newYork)

    @Test
    fun `gives the spring-forward day 23 hours, still starting at midnight`() {
        // 2027-03-14 is the US spring-forward date.
        val bounds = localDayBounds(local(2027, 3, 14, 12, zone = newYork), 0, newYork)

        assertEquals(0, at(bounds.start).hour)
        assertEquals(14, at(bounds.start).dayOfMonth)
        assertEquals(23 * MS_PER_HOUR - 1, bounds.end - bounds.start)
    }

    @Test
    fun `gives the fall-back day 25 hours, still starting at midnight`() {
        // 2027-11-07 is the US fall-back date.
        val bounds = localDayBounds(local(2027, 11, 7, 12, zone = newYork), 0, newYork)

        assertEquals(0, at(bounds.start).hour)
        assertEquals(7, at(bounds.start).dayOfMonth)
        assertEquals(25 * MS_PER_HOUR - 1, bounds.end - bounds.start)
    }

    @Test
    fun `lands on midnight when stepping onto a transition day`() {
        val bounds = localDayBounds(local(2027, 3, 13, 12, zone = newYork), 1, newYork)

        assertEquals(0, at(bounds.start).hour)
        assertEquals(14, at(bounds.start).dayOfMonth)
    }

    /**
     * The two cases a division by 86_400_000 gets wrong. Spring forward puts 23
     * hours between the midnights, which truncates to nothing and reports the
     * next day as today; fall back puts 25 between them, which a ceiling would
     * report as two. Either way a refill warning keyed off the count arrives on
     * the wrong day, once each spring and once each autumn.
     */
    @Test
    fun `counts one day across spring forward, though only 23 hours passed`() {
        assertEquals(
            1,
            localCalendarDaysBetween(
                local(2027, 3, 14, 9, zone = newYork),
                local(2027, 3, 15, 9, zone = newYork),
                newYork,
            ),
        )
    }

    @Test
    fun `counts one day across fall back, though 25 hours passed`() {
        assertEquals(
            1,
            localCalendarDaysBetween(
                local(2027, 11, 7, 9, zone = newYork),
                local(2027, 11, 8, 9, zone = newYork),
                newYork,
            ),
        )
    }

    @Test
    fun `counts a week spanning a transition as seven days`() {
        assertEquals(
            7,
            localCalendarDaysBetween(
                local(2027, 3, 11, 9, zone = newYork),
                local(2027, 3, 18, 9, zone = newYork),
                newYork,
            ),
        )
    }
}
