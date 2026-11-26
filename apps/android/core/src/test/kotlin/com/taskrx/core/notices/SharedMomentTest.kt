package com.taskrx.core.notices

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val ZONE = ZoneOffset.UTC

private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

/** Thursday, 2026-11-26 at 10:30 UTC. */
private val NOW = at(2026, 11, 26, 10, 30)

class SharedMomentTest {
    @Test
    fun `explicit calendar date and time are read`() {
        assertEquals(
            SharedMoment(at(2026, 12, 4, 14, 30), "Dec 4 at 2:30pm"),
            readSharedMoment("Dentist appointment Dec 4 at 2:30pm", NOW, ZONE),
        )
    }

    @Test
    fun `explicit calendar date without time uses part of day or default 9am`() {
        assertEquals(
            SharedMoment(at(2026, 12, 4, 9, 0), "Dec 4"),
            readSharedMoment("Pay invoice by Dec 4", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 4, 14, 0), "Dec 4"),
            readSharedMoment("Delivery on Dec 4 in the afternoon", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 4, 18, 0), "Dec 4"),
            readSharedMoment("Dinner Dec 4 evening", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 4, 9, 0), "Dec 4"),
            readSharedMoment("Call Dec 4 morning", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 4, 19, 0), "Dec 4"),
            readSharedMoment("Dec 4 tonight", NOW, ZONE),
        )
    }

    @Test
    fun `explicit calendar date for today with no time bumps past 9am to next whole hour`() {
        assertEquals(
            SharedMoment(at(2026, 11, 26, 11, 0), "Nov 26"),
            readSharedMoment("Finish form by Nov 26", NOW, ZONE),
        )
        // At 23:30, next hour crosses midnight, so clamp to 23:00 on that date.
        assertEquals(
            SharedMoment(at(2026, 11, 26, 23, 0), "Nov 26"),
            readSharedMoment("Finish form by Nov 26", at(2026, 11, 26, 23, 30), ZONE),
        )
        // Before 9am, 9:00 is still in the future so it stays 9:00.
        assertEquals(
            SharedMoment(at(2026, 11, 26, 9, 0), "Nov 26"),
            readSharedMoment("Finish form by Nov 26", at(2026, 11, 26, 8, 0), ZONE),
        )
    }

    @Test
    fun `ambiguous, competing, or impossible calendar dates yield null`() {
        assertNull(readSharedMoment("Due on 05/06", NOW, ZONE))
        assertNull(readSharedMoment("Either Dec 4 or Dec 5", NOW, ZONE))
        assertNull(readSharedMoment("Due Feb 30", NOW, ZONE))
    }

    @Test
    fun `relative day words resolve relative to now`() {
        assertEquals(
            SharedMoment(at(2026, 11, 26, 17, 0), "today at 5pm"),
            readSharedMoment("Pick up groceries today at 5pm", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 26, 19, 0), "tonight"),
            readSharedMoment("Call Mom tonight", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 26, 20, 30), "tonight at 8:30pm"),
            readSharedMoment("Call Mom tonight at 8:30pm", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 27, 9, 0), "tomorrow"),
            readSharedMoment("Send report tomorrow", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 27, 14, 0), "tomorrow"),
            readSharedMoment("Send report tomorrow afternoon", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 26, 11, 0), "today"),
            readSharedMoment("Pick up meds today", NOW, ZONE),
        )
    }

    @Test
    fun `weekday names resolve to upcoming occurrence and respect same-day times and next`() {
        // NOW is Thursday 2026-11-26 10:30.
        assertEquals(
            SharedMoment(at(2026, 11, 27, 15, 0), "Friday at 3pm"),
            readSharedMoment("Meeting Friday at 3pm", NOW, ZONE),
        )
        // Same weekday with future time stays today; past time or no time or "next" moves to next week.
        assertEquals(
            SharedMoment(at(2026, 11, 26, 14, 0), "Thursday at 2pm"),
            readSharedMoment("Meeting Thursday at 2pm", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 3, 9, 0), "Thursday at 9am"),
            readSharedMoment("Meeting Thursday at 9am", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 3, 9, 0), "Thursday"),
            readSharedMoment("Bins move to Thursday", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 12, 3, 14, 0), "next Thursday at 2pm"),
            readSharedMoment("Meeting next Thursday at 2pm", NOW, ZONE),
        )
        // Duplicate mentions of the same day are fine.
        assertEquals(
            SharedMoment(at(2026, 11, 27, 9, 0), "Friday"),
            readSharedMoment("Friday is the deadline; let's finish Fri", NOW, ZONE),
        )
    }

    @Test
    fun `competing relative days yield null`() {
        assertNull(readSharedMoment("Either tomorrow or Monday", NOW, ZONE))
    }

    @Test
    fun `time alone lands today when ahead and tomorrow when already past`() {
        assertEquals(
            SharedMoment(at(2026, 11, 26, 16, 0), "4pm"),
            readSharedMoment("Call the pharmacy at 4pm", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 27, 9, 30), "9:30am"),
            readSharedMoment("Call the pharmacy at 9:30am", NOW, ZONE),
        )
        assertEquals(
            SharedMoment(at(2026, 11, 26, 12, 0), "noon"),
            readSharedMoment("Lunch at noon", NOW, ZONE),
        )
    }

    @Test
    fun `plain text with no date or time yields null`() {
        assertNull(readSharedMoment("Buy coffee beans", NOW, ZONE))
        // Part-of-day word alone without a date or clock time does not guess.
        assertNull(readSharedMoment("Good morning! Pick up coffee beans", NOW, ZONE))
    }

    @Test
    fun `default zone overload runs cleanly`() {
        assertNull(readSharedMoment("No date here", NOW))
    }
}
