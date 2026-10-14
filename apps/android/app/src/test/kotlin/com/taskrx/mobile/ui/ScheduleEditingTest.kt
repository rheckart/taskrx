package com.taskrx.mobile.ui

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.isWellFormedSchedule
import com.taskrx.mobile.ui.forms.ScheduleType
import com.taskrx.mobile.ui.forms.defaultSchedule
import com.taskrx.mobile.ui.forms.hourMinuteToTimeInput
import com.taskrx.mobile.ui.forms.instantHourMinute
import com.taskrx.mobile.ui.forms.isValidTimeInput
import com.taskrx.mobile.ui.forms.parseCount
import com.taskrx.mobile.ui.forms.parseIntervalHours
import com.taskrx.mobile.ui.forms.pickedInstant
import com.taskrx.mobile.ui.forms.pickerDateMillis
import com.taskrx.mobile.ui.forms.scheduleTypeOf
import com.taskrx.mobile.ui.forms.timeInputToHourMinute
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
    fun `a picked time is stored as 24-hour HH MM, padded`() {
        assertEquals("08:05", hourMinuteToTimeInput(8, 5))
        assertEquals("23:59", hourMinuteToTimeInput(23, 59))
        assertEquals("00:00", hourMinuteToTimeInput(0, 0))
    }

    @Test
    fun `a stored time is read back as an hour and a minute, or not at all`() {
        assertEquals(8 to 5, timeInputToHourMinute("08:05"))
        assertEquals(23 to 59, timeInputToHourMinute("23:59"))
        assertNull(timeInputToHourMinute("25:99"))
        assertNull(timeInputToHourMinute("9:00"))
        assertNull(timeInputToHourMinute(""))
    }

    @Test
    fun `the day the date picker returns is the day the person tapped, west of UTC`() {
        val newYork = ZoneId.of("America/New_York")
        // The picker answers midnight UTC of the tapped day. Read in New York that is 8 pm the day before.
        val tapped = java.time.LocalDate.of(2026, 8, 4).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val picked = pickedInstant(tapped, 9, 30, newYork)

        assertEquals(java.time.ZonedDateTime.of(2026, 8, 4, 9, 30, 0, 0, newYork).toInstant().toEpochMilli(), picked)
    }

    @Test
    fun `the day the date picker returns is the day the person tapped, east of UTC`() {
        val auckland = ZoneId.of("Pacific/Auckland")
        val tapped = java.time.LocalDate.of(2026, 8, 4).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

        val picked = pickedInstant(tapped, 9, 30, auckland)

        assertEquals(java.time.ZonedDateTime.of(2026, 8, 4, 9, 30, 0, 0, auckland).toInstant().toEpochMilli(), picked)
    }

    @Test
    fun `a moment opens the pickers on its own local day and time, and comes back unchanged`() {
        val newYork = ZoneId.of("America/New_York")
        // 9:30 pm in New York on Aug 4 is already Aug 5 in UTC: the case a wrong zone gets wrong.
        val ms = java.time.ZonedDateTime.of(2026, 8, 4, 21, 30, 0, 0, newYork).toInstant().toEpochMilli()
        val (hour, minute) = instantHourMinute(ms, newYork)

        assertEquals(21 to 30, hour to minute)
        assertEquals(ms, pickedInstant(pickerDateMillis(ms, newYork), hour, minute, newYork))
    }

    @Test
    fun `the date picker opens on midnight UTC of the person's local day, in any zone`() {
        val aug4 = java.time.LocalDate.of(2026, 8, 4).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        for (name in listOf("America/New_York", "Pacific/Auckland", "UTC")) {
            val zone = ZoneId.of(name)
            val morning = java.time.ZonedDateTime.of(2026, 8, 4, 7, 15, 0, 0, zone).toInstant().toEpochMilli()
            val night = java.time.ZonedDateTime.of(2026, 8, 4, 23, 45, 0, 0, zone).toInstant().toEpochMilli()

            assertEquals(aug4, pickerDateMillis(morning, zone), "$name morning")
            assertEquals(aug4, pickerDateMillis(night, zone), "$name night")
        }
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
