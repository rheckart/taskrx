package com.taskrx.mobile.ui

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.isWellFormedSchedule
import com.taskrx.mobile.ui.forms.ScheduleType
import com.taskrx.mobile.ui.forms.defaultSchedule
import com.taskrx.mobile.ui.forms.inputToInstant
import com.taskrx.mobile.ui.forms.instantToInput
import com.taskrx.mobile.ui.forms.isValidTimeInput
import com.taskrx.mobile.ui.forms.parseCount
import com.taskrx.mobile.ui.forms.parseIntervalHours
import com.taskrx.mobile.ui.forms.scheduleTypeOf
import com.taskrx.mobile.ui.forms.toggleDay
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduleEditingTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `every variant starts from a default that can be saved`() {
        for (type in ScheduleType.entries) {
            val schedule = defaultSchedule(type, now)
            assertTrue(isWellFormedSchedule(schedule), "$type")
            assertEquals(type, scheduleTypeOf(schedule))
        }
    }

    @Test
    fun `the defaults are the common case for each variant`() {
        assertEquals(DailyTimes(listOf("09:00")), defaultSchedule(ScheduleType.Daily, now))
        assertEquals(WeeklyTimes(listOf(1, 2, 3, 4, 5), listOf("09:00")), defaultSchedule(ScheduleType.Weekly, now))
        assertEquals(IntervalTimes(8, now), defaultSchedule(ScheduleType.Interval, now))
        // An hour out, so the moment is ahead of the person filling the form in.
        assertEquals(OnceTimes(now + 3_600_000L), defaultSchedule(ScheduleType.Once, now))
        assertEquals(NoneTimes, defaultSchedule(ScheduleType.None, now))
    }

    @Test
    fun `toggling a day keeps Sunday-first order however the person tapped`() {
        assertEquals(listOf(0, 1, 2), toggleDay(listOf(2, 1), 0))
        assertEquals(listOf(1, 3), toggleDay(listOf(1, 2, 3), 2))
        assertEquals(emptyList(), toggleDay(listOf(4), 4))
    }

    @Test
    fun `a time is valid only as strict 24 hour HH colon MM`() {
        assertTrue(isValidTimeInput("00:00"))
        assertTrue(isValidTimeInput("23:59"))
        assertFalse(isValidTimeInput("24:00"))
        assertFalse(isValidTimeInput("09:60"))
        assertFalse(isValidTimeInput("9:00"))
        assertFalse(isValidTimeInput(""))
    }

    @Test
    fun `interval hours are leading digits, and anything else is zero`() {
        assertEquals(8, parseIntervalHours("8"))
        assertEquals(12, parseIntervalHours(" 12h"))
        assertEquals(0, parseIntervalHours(""))
        assertEquals(0, parseIntervalHours("0"))
        assertEquals(0, parseIntervalHours("abc"))
        assertEquals(0, parseIntervalHours("-3"))
    }

    @Test
    fun `an instant is typed and read back in the local zone`() {
        val zone = ZoneId.of("America/New_York")
        val ms = inputToInstant("2026-08-04T09:30", zone)!!

        assertEquals("2026-08-04T09:30", instantToInput(ms, zone))
        assertEquals(java.time.ZonedDateTime.of(2026, 8, 4, 9, 30, 0, 0, zone).toInstant().toEpochMilli(), ms)
    }

    @Test
    fun `a half-typed or impossible instant is not an instant yet`() {
        assertNull(inputToInstant("2026-08-04T09:"))
        assertNull(inputToInstant("2026-13-04T09:30"))
        assertNull(inputToInstant("2026-02-30T09:30"))
        assertNull(inputToInstant(""))
    }

    @Test
    fun `a count keeps a trailing point so 1 point 5 can be typed one character at a time`() {
        assertEquals(1.0, parseCount("1."))
        assertEquals(1.5, parseCount("1.5"))
        assertEquals(0.5, parseCount(".5"))
        assertEquals(7.0, parseCount("7abc"))
        assertEquals(0.0, parseCount("0"))
    }

    @Test
    fun `an empty or non-numeric count is no value, not zero`() {
        assertNull(parseCount(""))
        assertNull(parseCount("abc"))
        assertNull(parseCount("."))
    }
}
