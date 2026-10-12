package com.taskrx.core.schedule

import com.taskrx.core.domain.Priority
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val MS_PER_HOUR = 3_600_000L

private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

/**
 * Mirrors the `Reminder(...)` helper in `ReminderScheduleEngineTests.cs`. The
 * id is fixed rather than a fresh Guid so occurrence ids are readable in
 * failure output.
 */
private fun reminder(schedule: ScheduleTimes): Reminder =
    createReminder(
        id = "11111111-1111-1111-1111-111111111111",
        userId = "user-1",
        kind = ReminderKind.Medication,
        name = "Test med",
        dosage = "10mg",
        schedule = schedule,
        priority = Priority.Critical,
        createdAt = 0,
        updatedAt = 0,
    )

/** Local wall-clock helper. The suite runs under `UTC` unless a zone is passed. */
private fun local(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

private fun zoned(ms: Long, zone: ZoneId = ZoneId.systemDefault()): ZonedDateTime =
    Instant.ofEpochMilli(ms).atZone(zone)

/** The local hour and minute a fire time lands on, as `"HH:MM"`. */
private fun localTime(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = zoned(ms, zone)
    return "%02d:%02d".format(d.hour, d.minute)
}

/** Elapsed ms between consecutive occurrences. */
private fun gaps(occurrences: List<ScheduledOccurrence>): List<Long> =
    occurrences.zipWithNext { a, b -> b.fireAt - a.fireAt }

private fun fireTimes(occurrences: List<ScheduledOccurrence>): List<Long> = occurrences.map { it.fireAt }

class NextOccurrencesDailyTest {
    // NextOccurrences_Daily_RespectsCount
    @Test
    fun `respects count and tags each occurrence with the reminder`() {
        val r = reminder(dailyTimes(listOf("09:00")))
        val from = local(2026, 7, 3)

        val occ = nextOccurrences(r, from, 5)

        assertEquals(5, occ.size)
        assertEquals(r.id, occ[0].reminderId)
        assertEquals(r.name, occ[0].name)
        assertEquals(r.dosage, occ[0].dosage)
        assertEquals("${r.id}:${occ[0].fireAt}", occ[0].occurrenceId)
        assertEquals(
            listOf(
                local(2026, 7, 3, 9),
                local(2026, 7, 4, 9),
                local(2026, 7, 5, 9),
                local(2026, 7, 6, 9),
                local(2026, 7, 7, 9),
            ),
            fireTimes(occ),
        )
    }

    // NextOccurrences_Daily_MultipleTimes_OrderedByTime
    @Test
    fun `orders multiple times within a day chronologically`() {
        val r = reminder(dailyTimes(listOf("21:00", "09:00", "15:00")))
        val from = local(2026, 7, 3, 6)

        val occ = nextOccurrences(r, from, 3)

        assertEquals(listOf("09:00", "15:00", "21:00"), occ.map { localTime(it.fireAt) })
    }

    @Test
    fun `orders two times inside the same hour by minute`() {
        val r = reminder(dailyTimes(listOf("09:30", "09:00")))

        val occ = nextOccurrences(r, local(2026, 7, 3), 2)

        assertEquals(listOf("09:00", "09:30"), occ.map { localTime(it.fireAt) })
    }

    @Test
    fun `skips slots earlier in the day than from and rolls to tomorrow`() {
        val r = reminder(dailyTimes(listOf("09:00")))

        val occ = nextOccurrences(r, local(2026, 7, 3, 10), 1)

        assertEquals(listOf(local(2026, 7, 4, 9)), fireTimes(occ))
    }

    @Test
    fun `excludes a dose landing exactly on from`() {
        val r = reminder(dailyTimes(listOf("09:00")))

        val occ = nextOccurrences(r, local(2026, 7, 3, 9), 1)

        assertEquals(listOf(local(2026, 7, 4, 9)), fireTimes(occ))
    }

    @Test
    fun `returns nothing for an empty time list`() {
        assertEquals(emptyList(), nextOccurrences(reminder(dailyTimes(emptyList())), local(2026, 7, 3), 64))
    }

    @Test
    fun `ignores time strings it cannot parse`() {
        val r = reminder(dailyTimes(listOf("09:00", "nope", "25:00", "09:61")))

        val occ = nextOccurrences(r, local(2026, 7, 3), 2)

        assertEquals(listOf(local(2026, 7, 3, 9), local(2026, 7, 4, 9)), fireTimes(occ))
    }

    @Test
    fun `returns nothing when every time string is unparseable`() {
        val r = reminder(dailyTimes(listOf("breakfast", "bedtime")))

        assertEquals(emptyList(), nextOccurrences(r, local(2026, 7, 3), 5))
    }

    @Test
    fun `stops at the 60-day horizon rather than filling count`() {
        val r = reminder(dailyTimes(listOf("09:00")))

        val occ = nextOccurrences(r, local(2026, 7, 3), 100)

        assertEquals(60, occ.size)
        // JS `Date` normalizes day-of-month overflow; java.time does it with plusDays.
        val last = LocalDate.of(2026, 7, 3).plusDays(59)
        assertEquals(
            local(last.year, last.monthValue, last.dayOfMonth, 9),
            occ[59].fireAt,
        )
    }
}

class NextOccurrencesIntervalTest {
    // NextOccurrences_Interval_FastForwardsPastFromUtc
    @Test
    fun `fast-forwards past from from the start instant`() {
        val start = utc(2026, 7, 3, 0, 0)
        val r = reminder(intervalTimes(8, start))

        val occ = nextOccurrences(r, start + 20 * MS_PER_HOUR, 3)

        assertEquals(
            listOf(start + 24 * MS_PER_HOUR, start + 32 * MS_PER_HOUR, start + 40 * MS_PER_HOUR),
            fireTimes(occ),
        )
    }

    @Test
    fun `starts at the start instant when from precedes it`() {
        val start = utc(2026, 7, 3, 8, 0)
        val r = reminder(intervalTimes(8, start))

        val occ = nextOccurrences(r, start - MS_PER_HOUR, 3)

        assertEquals(listOf(start, start + 8 * MS_PER_HOUR, start + 16 * MS_PER_HOUR), fireTimes(occ))
    }

    @Test
    fun `lands on the next slot when from is an exact multiple of the interval`() {
        val start = utc(2026, 7, 3, 0, 0)
        val r = reminder(intervalTimes(8, start))

        val occ = nextOccurrences(r, start + 16 * MS_PER_HOUR, 1)

        assertEquals(listOf(start + 24 * MS_PER_HOUR), fireTimes(occ))
    }

    @Test
    fun `returns nothing for a non-positive interval`() {
        val start = utc(2026, 7, 3, 0, 0)

        assertEquals(emptyList(), nextOccurrences(reminder(intervalTimes(0, start)), start, 5))
        assertEquals(emptyList(), nextOccurrences(reminder(intervalTimes(-8, start)), start, 5))
    }

    @Test
    fun `is not capped by a day horizon`() {
        val start = utc(2026, 7, 3, 0, 0)
        val r = reminder(intervalTimes(8, start))

        val occ = nextOccurrences(r, start, 200)

        assertEquals(200, occ.size)
    }
}

/**
 * An occurrence landing exactly on `from` is EXCLUDED, matching
 * `ScheduleEngine.cs`'s `while (next <= fromUtc)`. The boundary is exactly where
 * an off-by-one produces a duplicate alarm at the moment a dose comes due.
 */
class FromBoundaryTest {
    private val start = utc(2026, 7, 3, 0, 0)

    @Test
    fun `excludes the interval occurrence exactly at from, as the C# engine does`() {
        val occ = nextOccurrences(reminder(intervalTimes(8, start)), start, 2)

        assertEquals(listOf(start + 8 * MS_PER_HOUR, start + 16 * MS_PER_HOUR), fireTimes(occ))
    }
}

class NextOccurrencesWeeklyTest {
    // NextOccurrences_Weekly_SkipsNonMatchingDays
    @Test
    fun `emits only on the selected weekdays`() {
        val r = reminder(weeklyTimes(listOf(1, 3, 5), listOf("07:00")))
        val from = local(2026, 7, 6) // a Monday

        val occ = nextOccurrences(r, from, 5)

        assertEquals(5, occ.size)
        for (o in occ) {
            assertEquals("07:00", localTime(o.fireAt))
            // Domain weekdays are 0 = Sunday; java.time's are 1 = Monday .. 7 = Sunday.
            assertContains(listOf(1, 3, 5), zoned(o.fireAt).dayOfWeek.value % 7)
        }
        assertEquals(
            listOf(
                local(2026, 7, 6, 7),
                local(2026, 7, 8, 7),
                local(2026, 7, 10, 7),
                local(2026, 7, 13, 7),
                local(2026, 7, 15, 7),
            ),
            fireTimes(occ),
        )
    }

    // NextOccurrences_Weekly_MultipleTimesPerDay_OrderedByTime
    @Test
    fun `orders multiple times within a selected day chronologically`() {
        val r = reminder(weeklyTimes(listOf(1), listOf("20:00", "08:00")))

        val occ = nextOccurrences(r, local(2026, 7, 6), 2)

        assertEquals(listOf("08:00", "20:00"), occ.map { localTime(it.fireAt) })
    }

    // NextOccurrences_Weekly_EmptyDaysOrTimes_ReturnsEmpty
    @Test
    fun `returns nothing when either the day set or the time list is empty`() {
        val from = local(2026, 7, 6)

        assertEquals(emptyList(), nextOccurrences(reminder(weeklyTimes(emptyList(), listOf("09:00"))), from, 5))
        assertEquals(emptyList(), nextOccurrences(reminder(weeklyTimes(listOf(1), emptyList())), from, 5))
    }

    @Test
    fun `excludes an occurrence landing exactly on from`() {
        val r = reminder(weeklyTimes(listOf(1), listOf("07:00")))

        val occ = nextOccurrences(r, local(2026, 7, 6, 7), 1)

        assertEquals(listOf(local(2026, 7, 13, 7)), fireTimes(occ))
    }

    @Test
    fun `stops at the 56-day horizon rather than filling count`() {
        val r = reminder(weeklyTimes(listOf(1), listOf("07:00")))

        val occ = nextOccurrences(r, local(2026, 7, 6), 100)

        assertEquals(8, occ.size)
        val last = LocalDate.of(2026, 7, 6).plusDays(49)
        assertEquals(local(last.year, last.monthValue, last.dayOfMonth, 7), occ[7].fireAt)
    }
}

class NextOccurrencesOnceTest {
    @Test
    fun `comes due exactly once, however many the caller asks for`() {
        val r = reminder(onceTimes(local(2026, 7, 10, 14)))

        val occ = nextOccurrences(r, local(2026, 7, 3), 64)

        assertEquals(listOf(local(2026, 7, 10, 14)), fireTimes(occ))
    }

    @Test
    fun `is spent once its moment has passed`() {
        val r = reminder(onceTimes(local(2026, 7, 10, 14)))

        assertEquals(emptyList(), nextOccurrences(r, local(2026, 7, 10, 14, 1), 64))
    }

    @Test
    fun `excludes a moment landing exactly on from, as every variant does`() {
        val at = local(2026, 7, 10, 14)
        val r = reminder(onceTimes(at))

        assertEquals(listOf(at), fireTimes(nextOccurrences(r, at - 1, 64)))
        assertEquals(emptyList(), nextOccurrences(r, at, 64))
    }
}

class NextOccurrencesNoneTest {
    @Test
    fun `never comes due, which is what puts a reminder in the Backlog`() {
        val r = reminder(noneTimes())

        assertEquals(emptyList(), nextOccurrences(r, local(2026, 7, 3), 64))
    }
}

class NextOccurrencesContractTest {
    // The vitest cases "throws on a schedule variant it does not know" and
    // "rejects a fractional count" have no Kotlin counterpart: `ScheduleTimes` is
    // a sealed interface, so the `when` in `nextOccurrences` is exhaustive at
    // compile time and an unknown variant cannot be forged (see the same note in
    // ScheduleTimesTest); and `count` is an `Int`, so it cannot be fractional.

    @Test
    fun `rejects a negative count`() {
        val r = reminder(dailyTimes(listOf("09:00")))

        assertFailsWith<IllegalArgumentException> { nextOccurrences(r, local(2026, 7, 3), -1) }
    }

    @Test
    fun `returns nothing for a count of zero`() {
        val r = reminder(dailyTimes(listOf("09:00")))

        assertEquals(emptyList(), nextOccurrences(r, local(2026, 7, 3), 0))
    }

    @Test
    fun `defaults count to 64, matching the C# signature`() {
        val start = utc(2026, 7, 3, 0, 0)

        assertEquals(64, nextOccurrences(reminder(intervalTimes(8, start)), start).size)
    }

    @Test
    fun `builds a stable occurrence id for the same reminder and fire time`() {
        val start = utc(2026, 7, 3, 8, 0)
        val r = reminder(intervalTimes(8, start))
        val from = start - MS_PER_HOUR

        val a = nextOccurrences(r, from, 1)
        val b = nextOccurrences(r, from, 1)

        assertEquals(a[0].occurrenceId, b[0].occurrenceId)
        assertEquals("${r.id}:$start", a[0].occurrenceId)
    }

    @Test
    fun `reads the device zone when none is given`() {
        // The suite pins the zone to UTC, so the default and an explicit UTC agree.
        val r = reminder(dailyTimes(listOf("09:00")))
        val from = utc(2026, 7, 3)

        assertEquals(
            nextOccurrences(r, from, 3, ZoneOffset.UTC),
            nextOccurrences(r, from, 3),
        )
        assertTrue(nextOccurrences(r, from, 3).isNotEmpty())
    }
}

/**
 * Daylight saving is where naive "add 86_400_000 ms" scheduling breaks: a 09:00
 * dose silently becomes 08:00 or 10:00 for half the year. Occurrences are built
 * from local calendar fields instead, so the wall-clock time the user chose is
 * what survives, and the gap between consecutive doses is the one that varies.
 * Every case here pins `America/New_York`.
 */
class NextOccurrencesDaylightSavingTest {
    @Test
    fun `keeps a daily 09_00 dose at 09_00 across spring forward`() {
        val r = reminder(dailyTimes(listOf("09:00")))
        // 2027-03-14 is the US spring-forward date.
        val occ = nextOccurrences(r, local(2027, 3, 13, 0, zone = NEW_YORK), 3, NEW_YORK)

        assertEquals(listOf("09:00", "09:00", "09:00"), occ.map { localTime(it.fireAt, NEW_YORK) })
        assertEquals(listOf(23 * MS_PER_HOUR, 24 * MS_PER_HOUR), gaps(occ))
    }

    @Test
    fun `keeps a daily 09_00 dose at 09_00 across fall back`() {
        val r = reminder(dailyTimes(listOf("09:00")))
        // 2027-11-07 is the US fall-back date.
        val occ = nextOccurrences(r, local(2027, 11, 6, 0, zone = NEW_YORK), 3, NEW_YORK)

        assertEquals(listOf("09:00", "09:00", "09:00"), occ.map { localTime(it.fireAt, NEW_YORK) })
        assertEquals(listOf(25 * MS_PER_HOUR, 24 * MS_PER_HOUR), gaps(occ))
    }

    @Test
    fun `keeps a weekly 07_00 dose at 07_00 across spring forward`() {
        val r = reminder(weeklyTimes(listOf(0), listOf("07:00"))) // Sundays
        val occ = nextOccurrences(r, local(2027, 3, 8, 0, zone = NEW_YORK), 2, NEW_YORK)

        assertEquals(listOf("07:00", "07:00"), occ.map { localTime(it.fireAt, NEW_YORK) })
        assertEquals(14, zoned(occ[0].fireAt, NEW_YORK).dayOfMonth) // the transition day
    }

    @Test
    fun `still emits a dose whose local time does not exist, shifted forward`() {
        val r = reminder(dailyTimes(listOf("02:30")))
        // 02:30 never happens on 2027-03-14; the clock jumps 02:00 -> 03:00.
        val occ = nextOccurrences(r, local(2027, 3, 13, 12, zone = NEW_YORK), 1, NEW_YORK)

        assertEquals(1, occ.size)
        assertEquals("03:30", localTime(occ[0].fireAt, NEW_YORK))
    }

    @Test
    fun `takes the first of a local time that happens twice on fall back`() {
        val r = reminder(dailyTimes(listOf("01:30")))
        // 2027-11-07 01:30 occurs at 05:30Z (EDT) and again at 06:30Z (EST).
        val occ = nextOccurrences(r, local(2027, 11, 6, 12, zone = NEW_YORK), 1, NEW_YORK)

        assertEquals(listOf(utc(2027, 11, 7, 5, 30)), fireTimes(occ))
    }

    @Test
    fun `keeps an interval schedule on fixed absolute spacing across a transition`() {
        val start = utc(2027, 3, 13, 12, 0)
        val r = reminder(intervalTimes(12, start))

        val occ = nextOccurrences(r, start, 3, NEW_YORK)

        assertEquals(
            listOf(start + 12 * MS_PER_HOUR, start + 24 * MS_PER_HOUR, start + 36 * MS_PER_HOUR),
            fireTimes(occ),
        )
    }
}
