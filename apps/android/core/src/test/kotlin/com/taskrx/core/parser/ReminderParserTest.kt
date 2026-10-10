package com.taskrx.core.parser

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.parseScheduleTimes
import com.taskrx.core.domain.serializeScheduleTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The parser is pure, so the identity and the clock it would otherwise have
 * reached for are supplied by the caller. Fixed values here keep the expected
 * `IntervalTimes.startAt` readable in failure output.
 */
private val NOW: Long = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

private val OPTIONS =
    ParseReminderOptions(
        id = "11111111-1111-1111-1111-111111111111",
        userId = "user-1",
        now = NOW,
    )

private fun parse(input: String): ReminderParseResult = parseReminder(input, OPTIONS)

/** Narrows the result to its reminder, failing with the parser's errors if it has none. */
private fun expectReminder(result: ReminderParseResult): Reminder =
    result.reminder ?: throw AssertionError("Expected a reminder, got errors: [${result.errors.joinToString(", ")}]")

/** The ten cases translated from `ReminderParserTests.cs`, in source order. */
class ParseReminderPortedXunitCasesTest {
    @Test
    fun `parses a medication with a named time and instructions`() {
        val result = parse("atorvastatin 20mg every morning with food")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("atorvastatin", reminder.name)
        assertEquals("20mg", reminder.dosage)
        assertEquals("with food", reminder.instructions)
        assertEquals(Priority.Normal, reminder.priority)
        assertEquals(dailyTimes(listOf("08:00")), reminder.schedule)
    }

    @Test
    fun `parses twice daily as 08 00 and 18 00`() {
        val result = parse("metformin 500mg twice daily")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("metformin", reminder.name)
        assertEquals("500mg", reminder.dosage)
        assertEquals(dailyTimes(listOf("08:00", "18:00")), reminder.schedule)
    }

    @Test
    fun `parses every N hours as an interval schedule`() {
        val result = parse("aspirin 81mg every 8 hours")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("aspirin", reminder.name)
        assertEquals("81mg", reminder.dosage)
        assertEquals(intervalTimes(8, NOW), reminder.schedule)
    }

    @Test
    fun `parses a task with weekly days and a time`() {
        val result = parse("do laundry every monday wednesday friday at 9am")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Task, reminder.kind)
        assertEquals("do laundry", reminder.name)
        assertEquals(weeklyTimes(listOf(1, 3, 5), listOf("09:00")), reminder.schedule)
    }

    @Test
    fun `reports an error for empty input`() {
        val result = parse("")

        assertNull(result.reminder)
        assertNotEquals(emptyList(), result.errors)
    }

    @Test
    fun `collects an unmatched token instead of failing`() {
        val result = parse("atorvastatin 20mg frobnicate")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertContains(result.unparsedTokens, "frobnicate")
        assertEquals("atorvastatin", reminder.name)
    }

    @Test
    fun `parses urgent as critical priority`() {
        val result = parse("urgent lisinopril 10mg daily")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals(Priority.Critical, reminder.priority)
        assertEquals("lisinopril", reminder.name)
        assertEquals("10mg", reminder.dosage)
        assertEquals(dailyTimes(listOf("09:00")), reminder.schedule)
    }

    @Test
    fun `parses the inventory and refill fields`() {
        val result = parse("atorvastatin 20mg daily 30 pills 1 per dose refill in 7 days")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("atorvastatin", reminder.name)
        assertEquals("20mg", reminder.dosage)
        assertEquals(30.0, reminder.pillsRemaining)
        assertEquals(1.0, reminder.pillsPerDose)
        assertEquals(7, reminder.refillReminderDays)
    }

    @Test
    fun `detects a medication from a drug suffix without a dosage`() {
        val result = parse("atorvastatin daily")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("atorvastatin", reminder.name)
        assertNull(reminder.dosage)
        assertEquals(dailyTimes(listOf("09:00")), reminder.schedule)
    }

    @Test
    fun `keeps a leading task verb in the name`() {
        val result = parse("take out trash weekly")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(ReminderKind.Task, reminder.kind)
        assertEquals("take out trash", reminder.name)
        assertEquals(weeklyTimes(emptyList(), emptyList()), reminder.schedule)
    }
}

class ParseReminderReadmeExampleTest {
    @Test
    fun `parses the documented happy path end to end`() {
        val reminder = expectReminder(parse("atorvastatin 20mg every morning with food"))

        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("20mg", reminder.dosage)
        assertEquals(dailyTimes(listOf("08:00")), reminder.schedule)
        assertEquals("with food", reminder.instructions)
    }

    @Test
    fun `takes its identity and timestamps from the caller`() {
        val reminder = expectReminder(parse("atorvastatin 20mg daily"))

        assertEquals(OPTIONS.id, reminder.id)
        assertEquals(OPTIONS.userId, reminder.userId)
        assertEquals(NOW, reminder.createdAt)
        assertEquals(NOW, reminder.updatedAt)
        assertEquals(true, reminder.active)
        assertEquals(emptyList(), reminder.tags)
        assertEquals(emptyList(), reminder.caregiverIds)
    }

    // Not in the vitest suite: the schedule the parser builds is the value that
    // crosses to Convex and to the TypeScript app, so it has to write and read
    // the exact JSON those two already store.
    @Test
    fun `a parsed schedule writes the JSON the TypeScript app stores`() {
        val schedule = expectReminder(parse("atorvastatin 20mg every morning at 9pm")).schedule
        val json = serializeScheduleTimes(schedule)
        val sample =
            Json.parseToJsonElement("""{"${'$'}type":"DailyTimes","Times":["08:00:00","21:00:00"]}""") as JsonObject

        assertEquals(sample, json)
        assertEquals(schedule, parseScheduleTimes(sample))
    }

    @Test
    fun `an interval schedule round-trips through the stored JSON`() {
        val schedule = expectReminder(parse("aspirin 81mg every 8 hours")).schedule
        val json = serializeScheduleTimes(schedule)

        assertEquals(
            Json.parseToJsonElement(
                """{"${'$'}type":"IntervalTimes","Hours":8,"StartAtUtc":"2026-01-15T12:00:00.000Z"}""",
            ),
            json,
        )
        assertEquals(schedule, parseScheduleTimes(json))
    }
}

class ParseReminderGarbledInputTest {
    @Test
    fun `never rejects the whole input`() {
        val result = parse("atorvastatin 20mg ###!!! frobnicate qwerty $$$")
        val reminder = expectReminder(result)

        assertEquals(emptyList(), result.errors)
        assertEquals(listOf("frobnicate", "qwerty"), result.unparsedTokens)
        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("atorvastatin", reminder.name)
        assertEquals("20mg", reminder.dosage)
        assertEquals(dailyTimes(listOf("09:00")), reminder.schedule)
    }

    @Test
    fun `does not throw on punctuation-only input`() {
        assertNull(parse("!!! ??? ***").reminder)
    }
}

class ParseReminderInputGuardsTest {
    @Test
    fun `reports an error for whitespace-only input`() {
        val result = parse("   \t\n ")

        assertNull(result.reminder)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(listOf("Input is empty."), result.errors)
    }

    @Test
    fun `reports an error when no token can serve as a name`() {
        val result = parse("daily")

        assertNull(result.reminder)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(listOf("Could not determine a name for the reminder."), result.errors)
    }
}

class ParseReminderKindDetectionTest {
    @Test
    fun `treats a short phrase with no drug signal as a task`() {
        assertEquals(ReminderKind.Task, expectReminder(parse("gym session")).kind)
    }

    @Test
    fun `treats a long phrase with no drug signal as a medication`() {
        val reminder = expectReminder(parse("water the plants downstairs today"))

        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("water plants downstairs today", reminder.name)
    }

    @Test
    fun `does not re-append a leading task verb on a medication`() {
        val reminder = expectReminder(parse("take atorvastatin 20mg daily"))

        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("atorvastatin", reminder.name)
    }

    // Not in the vitest suite (which the same mutation survives): the boundary of
    // "a short phrase is a chore" is three meaningful words, no more and no fewer.
    @Test
    fun `three meaningful words with no drug signal are still a task and four are a medication`() {
        assertEquals(ReminderKind.Task, expectReminder(parse("gym session today")).kind)
        assertEquals(ReminderKind.Medication, expectReminder(parse("gym session today now")).kind)
    }

    @Test
    fun `drops a noise word that is not a task verb from a task name`() {
        val reminder = expectReminder(parse("call the dentist"))

        assertEquals(ReminderKind.Task, reminder.kind)
        assertEquals("call dentist", reminder.name)
    }
}

class ParseReminderPriorityWordsTest {
    @Test
    fun `parses important as high`() {
        val reminder = expectReminder(parse("important call mom"))

        assertEquals(Priority.High, reminder.priority)
        assertEquals("call mom", reminder.name)
    }

    @Test
    fun `parses low priority as low`() {
        val reminder = expectReminder(parse("call plumber low priority"))

        assertEquals(Priority.Low, reminder.priority)
        assertEquals("call plumber", reminder.name)
    }

    @Test
    fun `keeps a bare low in the name`() {
        val reminder = expectReminder(parse("call plumber low bridge"))

        assertEquals(Priority.Normal, reminder.priority)
        assertEquals("call plumber low bridge", reminder.name)
    }

    @Test
    fun `keeps a trailing low in the name`() {
        assertEquals("call plumber low", expectReminder(parse("call plumber low")).name)
    }
}

class ParseReminderEveryPhrasesTest {
    @Test
    fun `parses every N days as an N times 24 hour interval`() {
        val reminder = expectReminder(parse("vitamin d 1000units every 3 days"))

        assertEquals(intervalTimes(72, NOW), reminder.schedule)
    }

    // Not in the vitest suite: `IntervalTimes.hours` is an Int here, where the
    // TypeScript held an unbounded double, so N*24 is capped rather than wrapped
    // into a negative interval.
    @Test
    fun `caps an every N days interval at the largest Int rather than wrapping`() {
        val reminder = expectReminder(parse("vitamin d 1000units every 2147483647 days"))

        assertEquals(intervalTimes(Int.MAX_VALUE, NOW), reminder.schedule)
    }

    @Test
    fun `ignores every with an unknown unit`() {
        val result = parse("aspirin 81mg every 8 bananas")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(listOf("8", "bananas"), result.unparsedTokens)
    }

    @Test
    fun `ignores every with a count but no unit`() {
        val result = parse("aspirin 81mg every 8")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(listOf("8"), result.unparsedTokens)
    }

    @Test
    fun `ignores a count that overflows a 32-bit int`() {
        val result = parse("aspirin 81mg every 99999999999 hours")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(listOf("99999999999", "hours"), result.unparsedTokens)
    }

    @Test
    fun `ignores every followed by an unknown word`() {
        val result = parse("aspirin 81mg every purple")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(listOf("purple"), result.unparsedTokens)
    }

    @Test
    fun `ignores a trailing every`() {
        val result = parse("atorvastatin 20mg every")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(emptyList(), result.unparsedTokens)
    }
}

class ParseReminderClockTimesTest {
    @Test
    fun `parses a bare 12-hour time without at`() {
        assertEquals(
            dailyTimes(listOf("20:30")),
            expectReminder(parse("atorvastatin 20mg 8:30pm")).schedule,
        )
    }

    @Test
    fun `parses a 24-hour time`() {
        assertEquals(
            dailyTimes(listOf("18:30")),
            expectReminder(parse("atorvastatin 20mg at 18:30")).schedule,
        )
    }

    @Test
    fun `truncates seconds`() {
        assertEquals(
            dailyTimes(listOf("08:00")),
            expectReminder(parse("atorvastatin 20mg at 8:00:00")).schedule,
        )
    }

    @Test
    fun `maps 12am to midnight and 12pm to noon`() {
        assertEquals(
            dailyTimes(listOf("00:00")),
            expectReminder(parse("atorvastatin 20mg at 12am")).schedule,
        )
        assertEquals(
            dailyTimes(listOf("12:00")),
            expectReminder(parse("atorvastatin 20mg at 12pm")).schedule,
        )
    }

    @Test
    fun `rejects an out-of-range hour`() {
        val result = parse("atorvastatin 20mg at 25:00")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(listOf("25:00"), result.unparsedTokens)
    }

    // Not in the vitest suite (which `hour >= 23` and `minute > 60` both survive):
    // the last valid 24-hour time and the first invalid one on each axis.
    @Test
    fun `accepts the last 24-hour hour and minute and rejects the first beyond them`() {
        assertEquals(dailyTimes(listOf("23:59")), expectReminder(parse("atorvastatin 20mg at 23:59")).schedule)
        assertEquals(listOf("24:00"), parse("atorvastatin 20mg at 24:00").unparsedTokens)
        assertEquals(listOf("8:60"), parse("atorvastatin 20mg at 8:60").unparsedTokens)
    }

    @Test
    fun `rejects out-of-range minutes`() {
        assertEquals(listOf("8:70"), parse("atorvastatin 20mg at 8:70").unparsedTokens)
    }

    @Test
    fun `rejects a 12-hour clock hour outside 1-12`() {
        assertEquals(listOf("13pm"), parse("atorvastatin 20mg at 13pm").unparsedTokens)
        assertEquals(listOf("0am"), parse("atorvastatin 20mg at 0am").unparsedTokens)
    }

    @Test
    fun `rejects a bare number as a time`() {
        assertEquals(listOf("8"), parse("atorvastatin 20mg at 8").unparsedTokens)
    }

    @Test
    fun `ignores a trailing at`() {
        val result = parse("atorvastatin 20mg at")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(emptyList(), result.unparsedTokens)
    }

    @Test
    fun `parses a bare named time`() {
        assertEquals(
            dailyTimes(listOf("21:00")),
            expectReminder(parse("atorvastatin 20mg bedtime")).schedule,
        )
    }

    @Test
    fun `merges and sorts times into an existing daily schedule`() {
        assertEquals(
            dailyTimes(listOf("09:00", "21:00")),
            expectReminder(parse("atorvastatin 20mg daily at 9pm")).schedule,
        )
    }

    @Test
    fun `de-duplicates a repeated time`() {
        assertEquals(
            dailyTimes(listOf("08:00")),
            expectReminder(parse("atorvastatin 20mg every morning at 8am")).schedule,
        )
    }

    @Test
    fun `replaces an interval schedule with a daily one (C# parity)`() {
        assertEquals(
            dailyTimes(listOf("09:00")),
            expectReminder(parse("aspirin 81mg every 8 hours at 9am")).schedule,
        )
    }

    // Not in the vitest suite: a time after a weekday folds into the weekly
    // schedule rather than replacing it (the `WeeklyTimes` arm of `mergeTime`).
    @Test
    fun `merges a time into an existing weekly schedule`() {
        assertEquals(
            weeklyTimes(listOf(1), listOf("09:00", "21:00")),
            expectReminder(parse("call mom monday at 9pm at 9am")).schedule,
        )
    }
}

class ParseReminderWeeklySchedulesTest {
    @Test
    fun `starts a weekly schedule from a bare day name`() {
        assertEquals(weeklyTimes(listOf(1), emptyList()), expectReminder(parse("call mom monday")).schedule)
    }

    @Test
    fun `de-duplicates and sorts day names`() {
        assertEquals(
            weeklyTimes(listOf(1, 5), emptyList()),
            expectReminder(parse("call mom friday monday monday")).schedule,
        )
    }

    @Test
    fun `accepts abbreviated day names`() {
        assertEquals(
            weeklyTimes(listOf(2, 4), emptyList()),
            expectReminder(parse("call mom tues thurs")).schedule,
        )
    }

    @Test
    fun `leaves an existing weekly schedule alone on weekly`() {
        assertEquals(
            weeklyTimes(listOf(1), emptyList()),
            expectReminder(parse("call mom monday weekly")).schedule,
        )
    }
}

class ParseReminderInventoryPhrasesTest {
    @Test
    fun `parses a bare pill count`() {
        val reminder = expectReminder(parse("atorvastatin 20mg 30 pills"))

        assertEquals(30.0, reminder.pillsRemaining)
        assertNull(reminder.pillsPerDose)
        assertNull(reminder.refillReminderDays)
    }

    @Test
    fun `parses a standalone per dose phrase`() {
        val result = parse("atorvastatin 20mg per dose")

        assertEquals(emptyList(), result.unparsedTokens)
        assertNull(expectReminder(result).pillsPerDose)
    }

    @Test
    fun `parses a fractional dose but leaves the count unparsed (C# parity)`() {
        val result = parse("atorvastatin 20mg 1.5 per dose")

        assertEquals(1.5, expectReminder(result).pillsPerDose)
        assertEquals(listOf("1.5"), result.unparsedTokens)
    }

    @Test
    fun `takes neither reading from N pills per dose (C# parity)`() {
        val result = parse("atorvastatin 20mg 30 pills per dose")
        val reminder = expectReminder(result)

        assertNull(reminder.pillsRemaining)
        assertNull(reminder.pillsPerDose)
        assertEquals(listOf("30", "pills"), result.unparsedTokens)
    }

    @Test
    fun `ignores a number followed by an unrelated word`() {
        assertEquals(listOf("30", "bananas"), parse("atorvastatin 20mg 30 bananas").unparsedTokens)
    }

    @Test
    fun `parses refill in N without the days suffix`() {
        val result = parse("atorvastatin 20mg refill in 7")

        assertEquals(7, expectReminder(result).refillReminderDays)
        assertEquals(emptyList(), result.unparsedTokens)
    }

    @Test
    fun `ignores refill in with a non-numeric count`() {
        val result = parse("atorvastatin 20mg refill in soon")

        assertNull(expectReminder(result).refillReminderDays)
        assertEquals(listOf("refill", "soon"), result.unparsedTokens)
    }

    @Test
    fun `ignores a trailing refill`() {
        val result = parse("atorvastatin 20mg refill")

        assertNull(expectReminder(result).refillReminderDays)
        assertEquals(listOf("refill"), result.unparsedTokens)
    }

    @Test
    fun `ignores a trailing count`() {
        assertEquals(listOf("30"), parse("atorvastatin 20mg 30").unparsedTokens)
    }
}

class ParseReminderInstructionPhrasesTest {
    @ParameterizedTest(name = "parses {0}")
    @CsvSource("with water, with water", "without food, without food", "after meals, after meals", "before meals, before meals")
    fun `parses an instruction phrase`(phrase: String, expected: String) {
        assertEquals(expected, expectReminder(parse("atorvastatin 20mg $phrase")).instructions)
    }

    @Test
    fun `ignores an unrecognized phrase after with`() {
        val result = parse("atorvastatin 20mg with juice")

        assertNull(expectReminder(result).instructions)
        assertEquals(listOf("juice"), result.unparsedTokens)
    }

    @Test
    fun `ignores a trailing with`() {
        val result = parse("atorvastatin 20mg with")

        assertNull(expectReminder(result).instructions)
        assertEquals(emptyList(), result.unparsedTokens)
    }
}

// Not in the vitest suite: V8 coverage counts an `&&` as one branch, Kover
// counts each operand, so each half of "per dose" needs a phrase that matches
// the first word and misses the second.
class ParseReminderNearMissInventoryPhrasesTest {
    @Test
    fun `reads a pill count followed by per and something other than dose`() {
        val result = parse("atorvastatin 20mg 30 pills per day")

        assertEquals(30.0, expectReminder(result).pillsRemaining)
        assertEquals(listOf("per", "day"), result.unparsedTokens)
    }

    @Test
    fun `leaves a number followed by per and something other than dose unparsed`() {
        val result = parse("atorvastatin 20mg 30 per day")

        assertNull(expectReminder(result).pillsPerDose)
        assertEquals(listOf("30", "per", "day"), result.unparsedTokens)
    }

    @Test
    fun `leaves a lone per followed by something other than dose unparsed`() {
        assertEquals(listOf("per", "day"), parse("atorvastatin 20mg per day").unparsedTokens)
    }

    @Test
    fun `reads a decimal followed by per and something other than dose as no dose`() {
        assertNull(expectReminder(parse("atorvastatin 20mg 1.5 per day")).pillsPerDose)
    }
}

// The lexicon and the patterns have no suite of their own in the TypeScript
// (they are gated through the parser). These pin the helpers whose branches the
// phrases above reach only indirectly, or not at all in the Kotlin port.
class ParserPatternHelpersTest {
    @Test
    fun `an integer token wider than a Long is no integer`() {
        assertNull(parseIntToken("99999999999999999999"))
        assertNull(parseIntToken(null))
        assertNull(parseIntToken("-3"))
        assertEquals(42, parseIntToken("42"))
    }

    @Test
    fun `a float token follows the Float number style without signs`() {
        assertEquals(1.0, parseFloatToken("1."))
        assertEquals(0.5, parseFloatToken(".5"))
        assertEquals(100000.0, parseFloatToken("1e5"))
        assertNull(parseFloatToken("-1"))
        assertNull(parseFloatToken("1.2.3"))
    }

    @Test
    fun `lookups are case-insensitive`() {
        assertEquals(1, lookupDayName("MONDAY"))
        assertEquals("08:00", lookupNamedTime("Morning"))
        assertTrue(isNoiseWord("THE"))
        assertTrue(isTaskVerb("Call"))
        assertTrue(isDoseUnit("Pills"))
        assertNotNull(DRUG_SUFFIXES.firstOrNull { it == "statin" })
    }
}
