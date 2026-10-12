package com.taskrx.core.adherence

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

private const val MS_PER_HOUR = 3_600_000L
private const val MS_PER_DAY = MS_PER_HOUR * 24

/** Local wall-clock helper, in the zone the suite runs at (`UTC`) unless told otherwise. */
private fun local(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

class AdherencePeriodTest {
    @Test
    fun `ends at the last instant of the day holding now`() {
        val period = adherencePeriod(local(2026, 8, 13, 9, 30), 7)

        assertEquals(local(2026, 8, 13, 23, 59) + 59_999, period.toMs)
    }

    @Test
    fun `starts at local midnight, so a whole first day is counted`() {
        val period = adherencePeriod(local(2026, 8, 13, 9, 30), 7)

        assertEquals(local(2026, 8, 7), period.fromMs)
    }

    @Test
    fun `counts the day holding now as one of the days`() {
        val period = adherencePeriod(local(2026, 8, 13, 9, 30), 1)

        assertEquals(local(2026, 8, 13), period.fromMs)
        assertEquals(local(2026, 8, 13, 23, 59) + 59_999, period.toMs)
    }

    @Test
    fun `reports the same window whatever time of day it is asked`() {
        val earlyMorning = adherencePeriod(local(2026, 8, 13, 0, 1), 7)
        val lateNight = adherencePeriod(local(2026, 8, 13, 23, 58), 7)

        assertEquals(earlyMorning, lateNight)
    }

    @Test
    fun `reaches back across a month boundary`() {
        val period = adherencePeriod(local(2026, 3, 2, 12), 7)

        assertEquals(local(2026, 2, 24), period.fromMs)
    }

    @Test
    fun `reaches back across a year boundary`() {
        val period = adherencePeriod(local(2026, 1, 3, 12), 7)

        assertEquals(local(2025, 12, 28), period.fromMs)
    }

    /**
     * The window a person actually reviews. ADR 0005 fixed the sweep's lookback at
     * seven days and said why the two numbers are the same one: "Adherence is
     * reported by day and reviewed by week, so a sweep that reaches a week back can
     * always account for every day a person is looking at."
     *
     * A period wider than the sweep's reach would show days whose unanswered doses
     * were never minted as Missed — the flattering figure this whole sequence
     * exists to remove, moved past day seven rather than fixed.
     */
    @Test
    fun `is the seven days the sweep can account for`() {
        assertEquals(7, ADHERENCE_PERIOD_DAYS)
    }
}

/**
 * Local calendar days, not fixed 24-hour blocks.
 *
 * A period built by subtracting `days * MS_PER_DAY` from `now` lands an hour
 * either side of midnight for half the year, so the first day of the window is
 * clipped or doubled and its bucket disagrees with every other day's. Building
 * from the zone's calendar date means the boundary is midnight because it was
 * asked for midnight, whatever the offset did in between.
 */
class AdherencePeriodDaylightSavingTest {
    private val newYork = ZoneId.of("America/New_York")

    @Test
    fun `starts at midnight across spring forward, not an hour off it`() {
        // 2027-03-14 is the US spring-forward date.
        val period = adherencePeriod(local(2027, 3, 16, 12, zone = newYork), 7, newYork)
        val start = java.time.Instant.ofEpochMilli(period.fromMs).atZone(newYork)

        assertEquals(0, start.hour)
        assertEquals(10, start.dayOfMonth)
        // The week the clocks went forward is an hour short of seven 24-hour days.
        assertEquals(7 * MS_PER_DAY - MS_PER_HOUR - 1, period.toMs - period.fromMs)
    }

    @Test
    fun `starts at midnight across fall back, not an hour off it`() {
        // 2027-11-07 is the US fall-back date.
        val period = adherencePeriod(local(2027, 11, 9, 12, zone = newYork), 7, newYork)
        val start = java.time.Instant.ofEpochMilli(period.fromMs).atZone(newYork)

        assertEquals(0, start.hour)
        assertEquals(3, start.dayOfMonth)
        // And the week they went back is an hour long.
        assertEquals(7 * MS_PER_DAY + MS_PER_HOUR - 1, period.toMs - period.fromMs)
    }
}
