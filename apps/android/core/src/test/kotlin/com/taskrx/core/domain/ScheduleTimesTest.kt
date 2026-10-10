package com.taskrx.core.domain

import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private fun utc(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

/** Writes with the production serializer and compares the exact JSON text. */
private fun wire(schedule: ScheduleTimes): String = serializeScheduleTimes(schedule).toString()

/**
 * Exhaustive dispatch over every variant. There is no `else`, so this compiles
 * only while `when` covers the whole sealed interface. It also proves each
 * variant carries its own distinct payload fields.
 */
private fun describeExhaustively(schedule: ScheduleTimes): String =
    when (schedule) {
        is DailyTimes -> "daily:${schedule.times.size}"
        is IntervalTimes -> "interval:${schedule.hours}"
        is WeeklyTimes -> "weekly:${schedule.daysOfWeek.size}"
        is OnceTimes -> "once:${schedule.at}"
        NoneTimes -> "none"
    }

class ScheduleTimesConstructorsTest {
    @Test
    fun `tags a daily schedule`() {
        assertEquals(DailyTimes(listOf("09:00", "21:00")), dailyTimes(listOf("09:00", "21:00")))
    }

    @Test
    fun `tags an interval schedule`() {
        val startAt = utc(2026, 1, 1, 6)
        assertEquals(IntervalTimes(hours = 8, startAt = startAt), intervalTimes(8, startAt))
    }

    @Test
    fun `tags a weekly schedule`() {
        assertEquals(
            WeeklyTimes(daysOfWeek = listOf(1, 3, 5), times = listOf("09:00")),
            weeklyTimes(listOf(1, 3, 5), listOf("09:00")),
        )
    }

    @Test
    fun `tags a single-moment schedule with the instant it happens at`() {
        assertEquals(OnceTimes(utc(2026, 8, 7, 14)), onceTimes(utc(2026, 8, 7, 14)))
    }

    @Test
    fun `tags an absent schedule, which carries no time at all`() {
        assertEquals(NoneTimes, noneTimes())
    }

    @Test
    fun `accepts an empty daily schedule, matching the C# default`() {
        assertEquals(DailyTimes(emptyList()), dailyTimes(emptyList()))
    }

    @Test
    fun `does not alias the caller's lists`() {
        val times = mutableListOf("09:00")
        val days = mutableListOf(1)
        val daily = dailyTimes(times)
        val weekly = weeklyTimes(days, times)

        times.add("21:00")
        days.add(2)

        assertEquals(listOf("09:00"), daily.times)
        assertEquals(WeeklyTimes(listOf(1), listOf("09:00")), weekly)
    }
}

class ScheduleTimesExhaustivenessTest {
    @Test
    fun `dispatches every variant without a fallback`() {
        assertEquals("daily:1", describeExhaustively(dailyTimes(listOf("09:00"))))
        assertEquals("interval:8", describeExhaustively(intervalTimes(8, 0)))
        assertEquals("once:1700000000000", describeExhaustively(onceTimes(1_700_000_000_000)))
        assertEquals("none", describeExhaustively(noneTimes()))
        assertEquals("weekly:2", describeExhaustively(weeklyTimes(listOf(1, 3), listOf("09:00"))))
    }

    // The two vitest cases that drive `assertNever` (an incomplete switch throwing
    // at runtime, and an unknown variant reaching `isWellFormedSchedule`) have no
    // Kotlin counterpart: a `when` over a sealed interface without an `else` is a
    // compile error until every variant is handled, and a variant cannot be
    // forged outside the sealed hierarchy. The guarantee they pinned is the
    // compiler's, and `describeExhaustively` above is the live proof of it.
}

class IsWellFormedScheduleTest {
    @Test
    fun `accepts a schedule that names at least one usable moment`() {
        assertTrue(isWellFormedSchedule(dailyTimes(listOf("09:00"))))
        assertTrue(isWellFormedSchedule(weeklyTimes(listOf(1), listOf("09:00"))))
        assertTrue(isWellFormedSchedule(intervalTimes(8, 0)))
        assertTrue(isWellFormedSchedule(onceTimes(1_700_000_000_000)))
    }

    @Test
    fun `accepts an absent schedule, because having no time is the point of it`() {
        assertTrue(isWellFormedSchedule(noneTimes()))
    }

    @Test
    fun `rejects a recurring schedule with no moment in it`() {
        // The misconfiguration `none` replaced: it produces nothing and looks like a
        // backlog item without being one.
        assertFalse(isWellFormedSchedule(dailyTimes(emptyList())))
        assertFalse(isWellFormedSchedule(dailyTimes(listOf("breakfast"))))
        assertFalse(isWellFormedSchedule(weeklyTimes(emptyList(), listOf("09:00"))))
        assertFalse(isWellFormedSchedule(weeklyTimes(listOf(1), emptyList())))
    }

    @Test
    fun `rejects an interval that never comes round`() {
        assertFalse(isWellFormedSchedule(intervalTimes(0, 0)))
        // The vitest case passes NaN for the start. A Long cannot be NaN; the
        // equivalent "not a moment" is an instant outside the range a date can hold.
        assertFalse(isWellFormedSchedule(intervalTimes(8, Long.MAX_VALUE)))
    }

    @Test
    fun `rejects a single moment that is not a moment`() {
        assertFalse(isWellFormedSchedule(onceTimes(Long.MIN_VALUE)))
    }

    @Test
    fun `accepts a single moment that has already passed, which is merely spent`() {
        assertTrue(isWellFormedSchedule(onceTimes(0)))
    }
}

class SerializeScheduleTimesTest {
    @Test
    fun `writes the C# converter's daily wire shape`() {
        assertEquals(
            """{"${'$'}type":"DailyTimes","Times":["09:00:00","21:30:00"]}""",
            wire(dailyTimes(listOf("09:00", "21:30"))),
        )
    }

    @Test
    fun `writes the C# converter's interval wire shape`() {
        assertEquals(
            """{"${'$'}type":"IntervalTimes","Hours":8,"StartAtUtc":"2026-01-01T06:00:00.000Z"}""",
            wire(intervalTimes(8, utc(2026, 1, 1, 6))),
        )
    }

    @Test
    fun `refuses to write a time it cannot represent as a TimeOnly`() {
        val daily = assertFailsWith<IllegalArgumentException> { serializeScheduleTimes(dailyTimes(listOf("25:00"))) }
        assertTrue("TimeOnly" in daily.message.orEmpty())
        val weekly =
            assertFailsWith<IllegalArgumentException> {
                serializeScheduleTimes(weeklyTimes(listOf(1), listOf("breakfast")))
            }
        assertTrue("TimeOnly" in weekly.message.orEmpty())
    }

    @Test
    fun `writes the C# converter's weekly wire shape`() {
        assertEquals(
            """{"${'$'}type":"WeeklyTimes","DaysOfWeek":[1,3,5],"Times":["08:00:00"]}""",
            wire(weeklyTimes(listOf(1, 3, 5), listOf("08:00"))),
        )
    }

    @Test
    fun `writes a single moment as an instant, the way an interval start is written`() {
        assertEquals(
            """{"${'$'}type":"OnceTimes","AtUtc":"2026-01-01T06:00:00.000Z"}""",
            wire(onceTimes(utc(2026, 1, 1, 6))),
        )
    }

    @Test
    fun `writes an absent schedule as a discriminator with no members`() {
        assertEquals("""{"${'$'}type":"NoneTimes"}""", wire(noneTimes()))
    }
}

class ScheduleTimesBoundaryTest {
    @Test
    fun `accepts the first and last minute of the day and rejects the hour after`() {
        // Kotlin-only: the vitest suite probes 25:00 but not the 24:00 edge.
        val edges = """{"${'$'}type":"DailyTimes","Times":["00:00","23:59"]}"""
        assertEquals(dailyTimes(listOf("00:00", "23:59")), parseScheduleTimes(json(edges)))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["24:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["23:60"]}""")))
    }

    @Test
    fun `pads a single-digit hour`() {
        assertEquals(
            dailyTimes(listOf("07:05")),
            parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["7:05"]}""")),
        )
    }

    @Test
    fun `writes instants the way toISOString does outside the common years`() {
        assertEquals(
            """{"${'$'}type":"OnceTimes","AtUtc":"1970-01-01T00:00:00.000Z"}""",
            wire(onceTimes(0)),
        )
        // Date's own limits: year 275760 is "+275760-...", year -271821 is "-271821-...".
        assertEquals(
            """{"${'$'}type":"OnceTimes","AtUtc":"+275760-09-13T00:00:00.000Z"}""",
            wire(onceTimes(8_640_000_000_000_000L)),
        )
        assertEquals(
            """{"${'$'}type":"OnceTimes","AtUtc":"-271821-04-20T00:00:00.000Z"}""",
            wire(onceTimes(-8_640_000_000_000_000L)),
        )
        // A year before 1 CE must not be folded onto a positive one (year-of-era).
        assertEquals(
            """{"${'$'}type":"OnceTimes","AtUtc":"-000001-01-01T00:00:00.000Z"}""",
            wire(onceTimes(-62_198_755_200_000L)),
        )
    }

    @Test
    fun `formats digits as ASCII whatever the default locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-SA-u-nu-arab"))
            assertEquals(dailyTimes(listOf("07:05")), parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["7:05"]}""")))
            assertEquals("""{"${'$'}type":"DailyTimes","Times":["07:05:00"]}""", wire(dailyTimes(listOf("07:05"))))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}

class ParseScheduleTimesTest {
    @Test
    fun `round-trips every variant through JSON`() {
        val schedules =
            listOf(
                dailyTimes(listOf("09:00", "21:30")),
                intervalTimes(8, utc(2026, 1, 1, 6)),
                weeklyTimes(listOf(0, 2, 6), listOf("08:00", "20:00")),
                onceTimes(utc(2026, 8, 7, 14)),
                noneTimes(),
            )

        for (schedule in schedules) {
            assertEquals(schedule, parseScheduleTimes(json(wire(schedule))))
        }
    }

    @Test
    fun `reads the JSON the TypeScript app and Convex store`() {
        // The literal samples the TypeScript suite writes, verbatim, as they sit in
        // a `schedule` column or a Convex document.
        assertEquals(
            dailyTimes(listOf("09:00", "21:30")),
            parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["09:00:00","21:30:00"]}""")),
        )
        assertEquals(
            intervalTimes(8, utc(2026, 1, 1, 6)),
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":8,"StartAtUtc":"2026-01-01T06:00:00.000Z"}"""),
            ),
        )
        assertEquals(
            weeklyTimes(listOf(1, 3, 5), listOf("08:00")),
            parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[1,3,5],"Times":["08:00:00"]}""")),
        )
        assertEquals(
            onceTimes(utc(2026, 1, 1, 6)),
            parseScheduleTimes(json("""{"${'$'}type":"OnceTimes","AtUtc":"2026-01-01T06:00:00.000Z"}""")),
        )
        assertEquals(noneTimes(), parseScheduleTimes(json("""{"${'$'}type":"NoneTimes"}""")))
    }

    @Test
    fun `reads TimeOnly's seconds and tick precision`() {
        assertEquals(
            dailyTimes(listOf("09:00", "21:30", "07:15")),
            parseScheduleTimes(
                json("""{"${'$'}type":"DailyTimes","Times":["09:00:00","21:30:00.0000000","07:15"]}"""),
            ),
        )
    }

    @Test
    fun `reads DateTimeOffset's +00_00 spelling of the epoch instant`() {
        assertEquals(
            intervalTimes(8, utc(2026, 1, 1, 6)),
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":8,"StartAtUtc":"2026-01-01T06:00:00+00:00"}"""),
            ),
        )
    }

    @Test
    fun `reads DayOfWeek as names as well as ordinals`() {
        assertEquals(
            weeklyTimes(listOf(0, 3, 6), listOf("08:00")),
            parseScheduleTimes(
                json(
                    """{"${'$'}type":"WeeklyTimes","DaysOfWeek":["Sunday","Wednesday","Saturday"],"Times":["08:00:00"]}""",
                ),
            ),
        )
    }

    @Test
    fun `reads a single moment written with an offset rather than a Z`() {
        assertEquals(
            onceTimes(utc(2026, 8, 7, 14)),
            parseScheduleTimes(json("""{"${'$'}type":"OnceTimes","AtUtc":"2026-08-07T14:00:00+00:00"}""")),
        )
    }

    @Test
    fun `rejects a single moment whose instant is missing or unreadable`() {
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"OnceTimes"}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"OnceTimes","AtUtc":"not a date"}""")))
        // Past what `Date` can hold: `Date.parse` is NaN, and the Long overflow must not escape as a throw.
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"OnceTimes","AtUtc":"+275761-01-01T00:00:00Z"}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"OnceTimes","AtUtc":"+999999999-12-31T23:59:59Z"}""")))
    }

    @Test
    fun `returns null for an unknown discriminator, matching the C# reader`() {
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"MonthlyTimes","Times":[]}""")))
    }

    @Test
    fun `returns null when the discriminator is absent`() {
        assertNull(parseScheduleTimes(json("""{"Times":["09:00:00"]}""")))
    }

    @Test
    fun `returns null for non-object input`() {
        assertNull(parseScheduleTimes(null))
        assertNull(parseScheduleTimes(json("null")))
        assertNull(parseScheduleTimes(json("\"DailyTimes\"")))
        assertNull(parseScheduleTimes(json("[]")))
    }

    @Test
    fun `rejects a daily payload whose Times is not an array of times`() {
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes"}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":"09:00"}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["25:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":["09:60"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"DailyTimes","Times":[9]}""")))
    }

    @Test
    fun `rejects an interval payload with a bad hours or start instant`() {
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"IntervalTimes","StartAtUtc":"2026-01-01T00:00:00Z"}""")))
        assertNull(
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":0,"StartAtUtc":"2026-01-01T00:00:00Z"}"""),
            ),
        )
        assertNull(
            parseScheduleTimes(json("""{"${'$'}type":"IntervalTimes","Hours":8,"StartAtUtc":"not-a-date"}""")),
        )
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"IntervalTimes","Hours":8}""")))
        // Beyond what the schedule engine's hour count can hold, or not whole, or not a number.
        assertNull(
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":[8],"StartAtUtc":"2026-01-01T00:00:00Z"}"""),
            ),
        )
        assertNull(
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":null,"StartAtUtc":"2026-01-01T00:00:00Z"}"""),
            ),
        )
        assertNull(
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":99999999999,"StartAtUtc":"2026-01-01T00:00:00Z"}"""),
            ),
        )
        assertNull(
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":1.5,"StartAtUtc":"2026-01-01T00:00:00Z"}"""),
            ),
        )
        assertNull(
            parseScheduleTimes(
                json("""{"${'$'}type":"IntervalTimes","Hours":"8","StartAtUtc":"2026-01-01T00:00:00Z"}"""),
            ),
        )
    }

    @Test
    fun `rejects a weekly payload with an out-of-range or unknown day`() {
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[7],"Times":["08:00:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[-1],"Times":["08:00:00"]}""")))
        assertNull(
            parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":["Caturday"],"Times":["08:00:00"]}""")),
        )
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[1.5],"Times":["08:00:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[null],"Times":["08:00:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[true],"Times":["08:00:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[[1]],"Times":["08:00:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","Times":["08:00:00"]}""")))
        assertNull(parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[1]}""")))
    }

    @Test
    fun `accepts an empty weekly day set, which the parser produces before days are known`() {
        assertEquals(
            weeklyTimes(emptyList(), listOf("08:00")),
            parseScheduleTimes(json("""{"${'$'}type":"WeeklyTimes","DaysOfWeek":[],"Times":["08:00:00"]}""")),
        )
    }
}
