package com.taskrx.mobile.format

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The day key round-trip, and the one-line mistake that breaks it. Mirrors datetime.test.ts.
 *
 * `computeAdherence` groups by local calendar date and hands back a "YYYY-MM-DD" key per
 * day. Reading it back as an instant (the obvious `parse` of a date-only string) lands on
 * UTC midnight, so every zone west of Greenwich names the day before. The bug is invisible
 * under this suite's default UTC, which is why these cases set a zone with a real offset.
 */
@RunWith(RobolectricTestRunner::class)
class DateFormatsTest {
    private val newYork = ZoneId.of("America/New_York")
    private val us = Locale.US

    private fun local(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0, zone: ZoneId = newYork) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `a day key lands on local midnight of the day it names`() {
        assertEquals(local(2026, 8, 4), dayKeyToLocalMs("2026-08-04", newYork))
    }

    @Test
    fun `a day key does not slip to the previous day in a western zone`() {
        val ms = dayKeyToLocalMs("2026-08-04", newYork)
        val date = java.time.Instant.ofEpochMilli(ms).atZone(newYork)
        assertEquals(4, date.dayOfMonth)
        assertEquals(0, date.hour)
    }

    @Test
    fun `a day key round-trips the first day of the year`() {
        assertEquals(local(2026, 1, 1), dayKeyToLocalMs("2026-01-01", newYork))
    }

    @Test
    fun `a day key lands on local midnight on the day the clocks go forward`() {
        // 2027-03-14 is the US spring-forward date, and 00:00 exists on it; 02:00 does not.
        val date = java.time.Instant.ofEpochMilli(dayKeyToLocalMs("2027-03-14", newYork)).atZone(newYork)
        assertEquals(14, date.dayOfMonth)
        assertEquals(0, date.hour)
    }

    @Test
    fun `formatDayKey names the day the key denotes and not the one before`() {
        assertEquals(formatDay(local(2026, 8, 4, 12), newYork, us), formatDayKey("2026-08-04", newYork, us))
        assertNotEquals(formatDay(local(2026, 8, 3, 12), newYork, us), formatDayKey("2026-08-04", newYork, us))
    }

    @Test
    fun `an instant and a key name the same day the same way`() {
        assertEquals(formatDay(local(2026, 8, 4, 21, 30), newYork, us), formatDayKey("2026-08-04", newYork, us))
    }

    @Test
    fun `an instant is named for the day it falls on locally, not the one UTC would`() {
        // 2026-08-05 01:00 UTC is still the 4th in New York, and the day a person runs out
        // is the day they experience.
        val utc = ZonedDateTime.of(2026, 8, 5, 1, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(formatDayKey("2026-08-04", newYork, us), formatDay(utc, newYork, us))
    }

    @Test
    fun `a day is named with its weekday`() {
        assertContains(formatDay(local(2026, 8, 4), newYork, us), "Tue")
    }

    @Test
    fun `a moment carries the day and the time`() {
        val text = formatDayTime(local(2026, 8, 4, 9, 5), newYork, us)
        assertContains(text, "Aug")
        assertContains(text, "9:05")
    }

    @Test
    fun `a moment read alone carries its year`() {
        val text = formatFullDayTime(local(2027, 8, 4, 9, 5), newYork, us)
        assertContains(text, "2027")
        assertContains(text, "Aug")
        assertContains(text, "9:05")
    }

    @Test
    fun `a time is written in the zone it is asked for`() {
        val utcNine = ZonedDateTime.of(2026, 8, 4, 9, 5, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertContains(formatTime(utcNine, ZoneId.of("UTC"), us), "9:05")
        assertContains(formatTime(utcNine, newYork, us), "5:05")
    }

    @Test
    fun `a 24 hour locale writes the hour on the 24 hour clock`() {
        assertContains(formatTime(local(2026, 8, 4, 21, 5), newYork, Locale.GERMANY), "21:05")
    }

    @Test
    fun `the day heading names today, its neighbours and then the date`() {
        val start = local(2026, 8, 2)
        assertEquals("Today", dayHeading(0, start, newYork, us))
        assertEquals("Yesterday", dayHeading(-1, start, newYork, us))
        assertEquals("Tomorrow", dayHeading(1, start, newYork, us))
        assertEquals(formatDay(start, newYork, us), dayHeading(-2, start, newYork, us))
    }
}
