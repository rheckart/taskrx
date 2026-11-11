package com.taskrx.core.parser

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.monthlyTimes
import com.taskrx.core.domain.parseScheduleTimes
import com.taskrx.core.domain.serializeScheduleTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val reminder = expectReminder(parse("check the boiler pressure tomorrow"))

        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("check boiler pressure tomorrow", reminder.name)
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

    // Length alone called chores of four words or more a medication, which opened
    // the review form on the wrong Kind with a Dosage field for a chore.
    @Test
    fun `a long phrase built from chore words is still a task`() {
        assertEquals(ReminderKind.Task, expectReminder(parse("take out the trash every two weeks")).kind)
        assertEquals(ReminderKind.Task, expectReminder(parse("water the plants downstairs")).kind)
        assertEquals(ReminderKind.Task, expectReminder(parse("pick up the recycling bins")).kind)
        assertEquals(ReminderKind.Task, expectReminder(parse("put the bins out")).kind)
    }

    @Test
    fun `takes out is not a drug name just because it is long`() {
        assertEquals("Water plants downstairs", expectReminder(parse("Water the plants downstairs")).name)
    }

    // The verb, the noise words after it and the particle are one phrase. Read
    // apart, the verb and the particle were dropped and the name was `trash`.
    @Test
    fun `keeps a chore's verb together with its particle`() {
        val result = parse("take out the trash")
        val reminder = expectReminder(result)

        assertEquals(ReminderKind.Task, reminder.kind)
        assertEquals("take out trash", reminder.name)
        assertEquals(emptyList(), result.unparsedTokens)
    }

    @Test
    fun `keeps a particle that has no noise word in front of it`() {
        assertEquals("put bins out", expectReminder(parse("put the bins out")).name)
        assertEquals("pick up bins", expectReminder(parse("pick the up bins")).name)
    }

    @Test
    fun `a chore verb with nothing after it is the whole name`() {
        assertEquals("take", expectReminder(parse("take the")).name)
    }

    @Test
    fun `a chore noun with no verb before it is the whole name`() {
        assertEquals("trash", expectReminder(parse("the trash")).name)
    }

    @Test
    fun `keeps a chore's words when the schedule comes first`() {
        val reminder = expectReminder(parse("take laundry at 9am"))

        assertEquals(ReminderKind.Task, reminder.kind)
        assertEquals("take laundry", reminder.name)
        assertEquals(dailyTimes(listOf("09:00")), reminder.schedule)
    }

    // A drug beats a chore word: `take` leads doses and chores both.
    @Test
    fun `a dose phrased with take is still a medication named without the verb`() {
        val reminder = expectReminder(parse("take ibuprofen 200mg every 8 hours"))

        assertEquals(ReminderKind.Medication, reminder.kind)
        assertEquals("ibuprofen", reminder.name)
        assertEquals(intervalTimes(8, NOW), reminder.schedule)
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

    @Test
    fun `parses every N weeks as an N times 168 hour interval`() {
        val reminder = expectReminder(parse("water the plants every 2 weeks"))

        assertEquals(intervalTimes(336, NOW), reminder.schedule)
    }

    // A phrase spelled out is as common as one with a digit, and misreading it is
    // worse than any other failure: the leftover words land in the name and the
    // interval falls back to daily, so the reminder looks right and fires 14 times
    // as often.
    @Test
    fun `reads a spoken count in every N units`() {
        val result = parse("Take out the trash every two weeks")
        val reminder = expectReminder(result)

        assertEquals(ReminderKind.Task, reminder.kind)
        assertEquals("Take out trash", reminder.name)
        assertEquals(emptyList(), result.unparsedTokens)
        assertEquals(intervalTimes(336, NOW), reminder.schedule)
    }

    @Test
    fun `reads a spoken count in hours and days`() {
        assertEquals(intervalTimes(8, NOW), expectReminder(parse("aspirin 81mg every eight hours")).schedule)
        assertEquals(intervalTimes(72, NOW), expectReminder(parse("vitamin d 1000units every three days")).schedule)
    }

    @Test
    fun `reads every a minute as one hour and every one week as 168 hours`() {
        assertEquals(intervalTimes(1, NOW), expectReminder(parse("aspirin 81mg every a minute")).schedule)
        assertEquals(intervalTimes(168, NOW), expectReminder(parse("aspirin 81mg every one week")).schedule)
    }

    // Minutes are the one unit IntervalTimes cannot hold, and an interval of zero
    // hours is not a schedule at all. Rounding up gives the shortest reminder the
    // domain can express; the draft shows the result before anything is saved.
    @Test
    fun `rounds a minutes interval up to the shortest one the domain can hold`() {
        assertEquals(
            intervalTimes(1, NOW),
            expectReminder(parse("aspirin 81mg every thirty minutes")).schedule,
        )
        assertEquals(intervalTimes(1, NOW), expectReminder(parse("aspirin 81mg every 15 mins")).schedule)
        assertEquals(intervalTimes(2, NOW), expectReminder(parse("aspirin 81mg every ninety minutes")).schedule)
    }

    @Test
    fun `reads the abbreviated and plural unit spellings`() {
        assertEquals(intervalTimes(2, NOW), expectReminder(parse("aspirin 81mg every 2 hrs")).schedule)
        assertEquals(intervalTimes(336, NOW), expectReminder(parse("water the plants every fortnight")).schedule)
        assertEquals(intervalTimes(336, NOW), expectReminder(parse("water the plants every one fortnight")).schedule)
        assertEquals(intervalTimes(672, NOW), expectReminder(parse("water the plants every two fortnights")).schedule)
        assertEquals(intervalTimes(336, NOW), expectReminder(parse("water the plants every 2 wks")).schedule)
        assertEquals(intervalTimes(96, NOW), expectReminder(parse("aspirin 81mg every 4 days")).schedule)
    }

    @Test
    fun `ignores a count of zero rather than writing an unusable interval`() {
        val result = parse("aspirin 81mg every 0 hours")

        assertEquals(dailyTimes(listOf("09:00")), expectReminder(result).schedule)
        assertEquals(listOf("0", "hours"), result.unparsedTokens)
    }

    @Test
    fun `keeps a spoken week interval that fits`() {
        assertEquals(intervalTimes(2016, NOW), expectReminder(parse("aspirin 81mg every twelve weeks")).schedule)
    }

    // `N * 168` overflows a Long at about 5.5e16, which is eight digits wider
    // than any Int, so it can only be reached with a count no phrase holds. The
    // guard is exercised directly instead.
    @Test
    fun `caps a week interval whose arithmetic overflows rather than wrapping to a negative`() {
        assertEquals(Int.MAX_VALUE, intervalHours(Long.MAX_VALUE, 168))
        assertNull(intervalHours(0, 168))
        assertNull(intervalHours(-3, 168))
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

/**
 * `monthly` is read in two halves: the parser sees the word and makes an empty
 * `monthly` schedule, and [readPhrase] fills in the dates and times from the one
 * phrase whose own words said `monthly`. Both halves are pinned here.
 */
class ParseReminderMonthlyTest {
    /** The phrase as the review form first shows it, which is where the `monthly` dates are read. */
    private fun read(phrase: String): ReminderDraft = readPhrase(phrase, ReadPhraseOptions(now = NOW)).draft

    @Test
    fun `the parser reads a date and a time once the month word is behind them`() {
        val result = parse("pay rent monthly on the 15th at 9am")
        val reminder = expectReminder(result)

        assertEquals(monthlyTimes(listOf(15), listOf("09:00")), reminder.schedule)
        assertEquals("pay rent", reminder.name)
        assertEquals(emptyList(), result.unparsedTokens)
    }

    @Test
    fun `the parser reads the month word with nothing after it as an empty monthly`() {
        assertEquals(monthlyTimes(emptyList(), emptyList()), expectReminder(parse("call landlord monthly")).schedule)
        assertEquals(monthlyTimes(emptyList(), emptyList()), expectReminder(parse("pay rent every month")).schedule)
    }

    @Test
    fun `a bare month is not a monthly schedule`() {
        // `this month's rent` is a reminder about a month, and the tokenizer splits `month's`.
        val reminder = expectReminder(parse("pay this month's rent"))

        assertEquals("pay this month s rent", reminder.name)
    }

    // A bare number is a day only while one has not been read: `monthly on the 9th at 9`
    // has a date before its clock time, and `monthly at 9` has none.
    @Test
    fun `the parser takes a bare number for a date before a clock time`() {
        assertEquals(monthlyTimes(listOf(9), listOf("09:00")), expectReminder(parse("pay rent monthly on the 9th at 9")).schedule)
        assertEquals(monthlyTimes(listOf(9), listOf("09:00")), expectReminder(parse("pay rent monthly 9 at 09:00")).schedule)
        assertEquals(monthlyTimes(emptyList(), listOf("09:00")), expectReminder(parse("pay rent monthly at 9")).schedule)
        // A number that is no date is still a stray, as it would be anywhere else.
        assertEquals(listOf("99th"), parse("pay rent monthly on the 99th").unparsedTokens)
    }

    @Test
    fun `the parser reads ordinals and keeps several dates and times apart`() {
        assertEquals(
            monthlyTimes(listOf(1, 15), listOf("09:00", "20:00")),
            expectReminder(parse("pay rent every month on the 15th and the 1st at 9am and 8pm")).schedule,
        )
    }

    @Test
    fun `a month word wins over a time read before it`() {
        // `15` is a clock time to the words before it; once `monthly` arrives the schedule is the
        // month's, and the `15` is left where it was: in the name.
        val reminder = expectReminder(parse("pay rent at 15 monthly on the 1st"))

        assertEquals(monthlyTimes(listOf(1), emptyList()), reminder.schedule)
    }

    @Test
    fun `a month word survives a schedule word that replaces it, and the month wins at the end`() {
        // `twice` replaces the schedule outright; the month word is restored once the walk is
        // over, so the phrase is still monthly rather than twice daily.
        assertEquals(monthlyTimes(emptyList(), emptyList()), expectReminder(parse("pay rent monthly twice daily")).schedule)
    }

    @Test
    fun `a month word wins over a weekday read after it, which leaves its day out`() {
        assertEquals(
            monthlyTimes(listOf(15), emptyList()),
            expectReminder(parse("pay rent monthly on the 15th Monday")).schedule,
        )
        // A clock time moves with it: the monthly times are what survive.
        assertEquals(
            monthlyTimes(listOf(15), listOf("09:00")),
            expectReminder(parse("pay rent monthly on the 15th at 9am Monday")).schedule,
        )
    }

    @Test
    fun `reads named times and hours behind an at`() {
        assertEquals(monthlyTimes(emptyList(), listOf("09:00")), expectReminder(parse("pay rent monthly at 9")).schedule)
        assertEquals(monthlyTimes(emptyList(), listOf("12:00")), expectReminder(parse("pay rent monthly at noon")).schedule)
        assertEquals(monthlyTimes(emptyList(), listOf("20:00")), expectReminder(parse("pay rent monthly at 8pm")).schedule)
        // After the one bare number a month can spend on a date, `24` is no time either.
        assertEquals(listOf("24"), parse("pay rent monthly the 1st at 24").unparsedTokens)
        assertEquals(emptyList(), parse("pay rent monthly at 12:30").unparsedTokens)
    }

    @Test
    fun `an ordinal is a date wherever it appears, so a word that is neither a date nor a time is no match`() {
        assertNull(tryParseOrdinalDay("0th"))
        assertNull(tryParseOrdinalDay("32nd"))
        assertNull(tryParseOrdinalDay("5"))
        assertEquals(31, tryParseOrdinalDay("31st"))
        assertEquals(21, tryParseOrdinalDay("21ST"))
    }

    @Test
    fun `treats a month word as part of the schedule rather than the name`() {
        assertEquals("pay rent", expectReminder(parse("pay rent every month")).name)
    }

    @Test
    fun `reads a date and a time with the month word after them`() {
        val draft = read("take out the trash the 1st at 9am monthly")

        assertEquals(ReminderKind.Task, draft.kind)
        assertEquals(monthlyTimes(listOf(1), listOf("09:00")), draft.schedule)
        assertEquals(emptyList(), draft.unparsedTokens)
    }

    @Test
    fun `reads the month word before the dates, in any order, with ordinals or not`() {
        assertEquals(
            monthlyTimes(listOf(1, 15), listOf("09:00")),
            read("pay rent every month on the 15th and the 1st at 09:00").schedule,
        )
        assertEquals(
            monthlyTimes(listOf(3), listOf("09:00")),
            read("pay rent monthly 3 at 9am").schedule,
        )
        assertEquals(
            monthlyTimes(listOf(21), listOf("09:00")),
            read("pay rent monthly the 21st at 9am").schedule,
        )
    }

    @Test
    fun `keeps several times written after the dates`() {
        assertEquals(
            monthlyTimes(listOf(1), listOf("08:00", "20:00")),
            read("pay rent monthly on the 1st at 8am and 8pm").schedule,
        )
    }

    @Test
    fun `a date out of range is no date, and the day of the month is the number in front of its suffix`() {
        assertNull(tryParseMonthDay("0"))
        assertNull(tryParseMonthDay("32"))
        assertNull(tryParseMonthDay("2nd2nd"))
        assertEquals(2, tryParseMonthDay("2ND"))
        assertEquals(23, tryParseMonthDay("23rd"))
    }

    @Test
    fun `a month word with no date is not savable, so the form asks for one`() {
        val draft = read("pay rent monthly")

        assertEquals(monthlyTimes(emptyList(), emptyList()), draft.schedule)
        assertFalse(isSavableDraft(draft))
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
        assertTrue(isTaskSignal("TRASH"))
        assertFalse(isTaskSignal("atorvastatin"))
        assertEquals(2, lookupNumberWord("Two"))
        assertNull(lookupNumberWord("21"))
        assertTrue(isDoseUnit("Pills"))
        assertNotNull(DRUG_SUFFIXES.firstOrNull { it == "statin" })
    }
}
