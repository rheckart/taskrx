package com.taskrx.core.notices

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val UTC: ZoneId = ZoneOffset.UTC

private fun nowAt(year: Int, month: Int, day: Int, hour: Int = 12): Long =
    ZonedDateTime.of(year, month, day, hour, 0, 0, 0, UTC).toInstant().toEpochMilli()

private fun read(text: String, now: Long = nowAt(2026, 11, 14)): ReadDate = readNoticeDate(text, now, UTC)

private fun found(text: String, now: Long = nowAt(2026, 11, 14)): ReadDate.Found {
    val reading = read(text, now)
    assertIs<ReadDate.Found>(reading, "expected a date in: $text")
    return reading
}

class ExtractTest {
    @Test
    fun `month names are read in either order, with an ordinal and an of`() {
        val expected = LocalDate.of(2026, 11, 18)
        for (text in listOf("Nov 18", "November 18", "November 18th", "18 November", "18th of November", "18th November")) {
            assertEquals(expected, found(text).date, text)
        }
        assertEquals(LocalDate.of(2026, 9, 3), found("Sept 3", nowAt(2026, 1, 1)).date)
    }

    @Test
    fun `a date with no year is the next such date at or after today`() {
        // Read on 14 November, the 18th is still ahead this year.
        assertEquals(LocalDate.of(2026, 11, 18), found("November 18").date)
        // Read on 20 November, it has passed, so the next one is a year later.
        assertEquals(LocalDate.of(2027, 11, 18), found("November 18", nowAt(2026, 11, 20)).date)
        // Today counts as "at or after", so it stays this year.
        assertEquals(LocalDate.of(2026, 11, 18), found("November 18", nowAt(2026, 11, 18)).date)
    }

    @Test
    fun `a date with a year is taken as written, even when it has passed`() {
        assertEquals(LocalDate.of(2024, 11, 18), found("November 18, 2024").date)
        assertEquals(LocalDate.of(2024, 11, 18), found("18 November 2024").date)
    }

    @Test
    fun `Feb 29 finds the next leap year when no year is named`() {
        assertEquals(LocalDate.of(2028, 2, 29), found("Feb 29").date)
        assertEquals(LocalDate.of(2026, 2, 28), found("Feb 28", nowAt(2026, 1, 1)).date)
    }

    @Test
    fun `ISO dates are read exactly`() {
        assertEquals(LocalDate.of(2026, 11, 18), found("on 2026-11-18 at noon").date)
        assertEquals(LocalDate.of(2026, 1, 2), found("2026-01-02").date)
    }

    @Test
    fun `a numeric date is read only when one component exceeds twelve`() {
        assertEquals(LocalDate.of(2026, 11, 18), found("18/11").date)
        assertEquals(LocalDate.of(2026, 11, 18), found("11/18").date)
        assertEquals(LocalDate.of(2026, 11, 18), found("18.11").date)
    }

    @Test
    fun `an ambiguous numeric date is refused rather than guessed`() {
        assertEquals(ReadDate.Unread(UnreadReason.Ambiguous), read("05/06"))
        assertEquals(ReadDate.Unread(UnreadReason.Ambiguous), read("11/12"))
        assertEquals(ReadDate.Unread(UnreadReason.Ambiguous), read("05-06"))
    }

    @Test
    fun `a numeric date with a four-digit year reads when one part can only be the day`() {
        assertEquals(LocalDate.of(2026, 11, 18), found("18/11/2026").date)
        assertEquals(LocalDate.of(2026, 12, 25), found("12/25/2026").date)
        assertEquals(LocalDate.of(2027, 1, 13), found("13.01.2027").date)
        assertEquals("12/25/2026", found("Collection on 12/25/2026.").matchedText)
    }

    @Test
    fun `a numeric date with a year in the past is that date, as a month name with a year is`() {
        assertEquals(LocalDate.of(2024, 11, 18), found("18/11/2024").date)
    }

    @Test
    fun `a numeric date with a year is still refused when either part could be the month`() {
        assertEquals(ReadDate.Unread(UnreadReason.Ambiguous), read("05/06/2026"))
    }

    @Test
    fun `a two-digit year is refused, because its century would be a guess`() {
        assertEquals(ReadDate.Unread(UnreadReason.Ambiguous), read("11/18/26"))
    }

    @Test
    fun `a numeric date with a year that is not a real date is impossible`() {
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("31/02/2026"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("13/13/2026"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("0/13/2026"))
    }

    @Test
    fun `a date-shaped text that is not a real date is impossible`() {
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("Feb 30"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("31/02"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("2026-13-01"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("32/11"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("18/00"))
        assertEquals(ReadDate.Unread(UnreadReason.Impossible), read("18/19"))
    }

    @Test
    fun `two different dates are refused`() {
        assertEquals(ReadDate.Unread(UnreadReason.Competing), read("Nov 18 or Nov 20"))
        assertEquals(ReadDate.Unread(UnreadReason.Competing), read("2026-11-18 and December 1"))
    }

    @Test
    fun `the same date said twice is still one date`() {
        assertEquals(LocalDate.of(2026, 11, 18), found("Nov 18 or 2026-11-18").date)
    }

    @Test
    fun `nothing date-shaped yields no date`() {
        assertEquals(ReadDate.Unread(UnreadReason.NoDate), read("The bins were collected today."))
        assertEquals(ReadDate.Unread(UnreadReason.NoDate), read(""))
    }

    @Test
    fun `an ISO date wins over the numeric date inside it`() {
        val reading = found("2026-11-18")
        assertEquals(LocalDate.of(2026, 11, 18), reading.date)
        assertEquals("2026-11-18", reading.matchedText)
    }

    @Test
    fun `times are read in each form the plan names`() {
        assertEquals(LocalTime.of(17, 0), found("Nov 18 at 5pm").time)
        assertEquals(LocalTime.of(17, 30), found("Nov 18 at 5:30 pm").time)
        assertEquals(LocalTime.of(17, 0), found("Nov 18 at 17:00").time)
        assertEquals(LocalTime.of(12, 0), found("Nov 18 at noon").time)
        assertEquals(LocalTime.of(0, 0), found("Nov 18 at midnight").time)
        assertEquals(LocalTime.of(0, 0), found("Nov 18 at 12am").time)
        assertEquals(LocalTime.of(12, 0), found("Nov 18 at 12pm").time)
        assertEquals(LocalTime.of(17, 0), found("Nov 18 at 5 pm").time)
    }

    @Test
    fun `no time is null, and the first time named wins`() {
        assertNull(found("Nov 18").time)
        assertEquals(LocalTime.NOON, found("Nov 18 at noon or 5pm").time)
    }

    @Test
    fun `a time that is not a real clock time is ignored`() {
        assertNull(found("Nov 18 at 13pm").time)
        assertNull(found("Nov 18 at 5:60 pm").time)
        assertNull(found("Nov 18 at 25:00").time)
        assertNull(found("Nov 18 at 10:60").time)
    }

    @Test
    fun `a found reading becomes a moment in the zone, with its own time or the fallback`() {
        val reading = found("Nov 18 at 5pm")
        assertEquals(
            ZonedDateTime.of(2026, 11, 18, 17, 0, 0, 0, UTC).toInstant().toEpochMilli(),
            reading.moment(UTC),
        )
        val noTime = found("Nov 18")
        assertEquals(
            ZonedDateTime.of(2026, 11, 18, 9, 0, 0, 0, UTC).toInstant().toEpochMilli(),
            noTime.moment(UTC),
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 18, 7, 30, 0, 0, UTC).toInstant().toEpochMilli(),
            noTime.moment(UTC, LocalTime.of(7, 30)),
        )
    }
}
