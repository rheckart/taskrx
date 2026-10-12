package com.taskrx.mobile.format

import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a person reads back when the app tells them when something happens. Mirrors
 * schedule.test.ts. This is the sentence natural-language entry shows before it saves, so
 * a schedule that renders as though it were set when it is not (an empty `daily`, a
 * `weekly` with no days) is the specific failure worth pinning.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduleFormatTest {
    private val utc = ZoneId.of("UTC")

    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 1, 15, h, m, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `names every daily time`() {
        assertEquals("Daily at 08:00, 18:00", describeSchedule(dailyTimes(listOf("08:00", "18:00")), utc))
    }

    @Test
    fun `says so when a daily schedule has no time to come due at`() {
        assertEquals("Daily (no times set)", describeSchedule(dailyTimes(emptyList()), utc))
    }

    @Test
    fun `names the weekdays and the times they apply on`() {
        assertEquals("Mon, Wed, Fri at 09:00", describeSchedule(weeklyTimes(listOf(1, 3, 5), listOf("09:00")), utc))
    }

    @Test
    fun `says so when a weekly schedule names no day`() {
        assertEquals("Weekly (not fully set)", describeSchedule(weeklyTimes(emptyList(), listOf("09:00")), utc))
    }

    @Test
    fun `says so when a weekly schedule names no time`() {
        assertEquals("Weekly (not fully set)", describeSchedule(weeklyTimes(listOf(2), emptyList()), utc))
    }

    @Test
    fun `names the gap and where an interval starts`() {
        assertEquals("Every 8h starting 07:30", describeSchedule(intervalTimes(8, at(7, 30)), utc))
    }

    @Test
    fun `writes an interval start as a two-digit clock time`() {
        assertEquals("Every 6h starting 07:05", describeSchedule(intervalTimes(6, at(7, 5)), utc))
    }

    @Test
    fun `an interval start is the local clock time in the zone asked for`() {
        assertEquals("Every 6h starting 02:05", describeSchedule(intervalTimes(6, at(7, 5)), ZoneId.of("America/New_York")))
    }

    @Test
    fun `says a single moment happens once`() {
        val text = describeSchedule(onceTimes(at(7, 30)), utc, Locale.US)
        assertTrue(text.startsWith("Once, "), text)
        assertContains(text, "7:30")
    }

    @Test
    fun `says a reminder with no time is in the backlog`() {
        assertEquals("In your backlog — no time set", describeSchedule(noneTimes(), utc))
    }
}
