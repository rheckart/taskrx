package com.taskrx.core.parser

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * Ported from `ScheduleGrammarParserTests` in
 * `TaskRx.Core.Tests/ScheduleGrammarParserTests.cs` in the retired .NET app,
 * by way of `schedule-grammar.test.ts`.
 *
 * The first class is the 17 xUnit cases, one-for-one and in source order. The
 * rest cover the grammar the C# suite exercises only indirectly — the
 * two-character letter codes, the 24-hour and bare-hour time forms, and every
 * rejection path — because those branches are what a natural-language parser
 * gets wrong first.
 *
 * `TimeOnly` has no JVM counterpart here, so times are asserted as the `"HH:MM"`
 * strings the domain uses everywhere else.
 */

private fun expectSuccess(result: ScheduleParseResult): ScheduleParseResult.Success {
    if (result is ScheduleParseResult.Failure) {
        throw AssertionError("Expected a successful parse, got: ${result.error}")
    }
    return assertIs<ScheduleParseResult.Success>(result)
}

private fun expectFailure(result: ScheduleParseResult): ScheduleParseResult.Failure {
    if (result is ScheduleParseResult.Success) {
        throw AssertionError("Expected a failed parse, got ${result.daysOfWeek} / ${result.times}")
    }
    return assertIs<ScheduleParseResult.Failure>(result)
}

/** The times of a parse that is expected to succeed. */
private fun timesOf(input: String): List<String> = expectSuccess(parseScheduleGrammar(input)).times

class ScheduleGrammarParserPortedTest {
    @Test
    fun `Parse_Empty_ReturnsError`() {
        val result = parseScheduleGrammar("")

        assertIs<ScheduleParseResult.Failure>(result)
        assertNotEquals("", expectFailure(result).error)
    }

    @Test
    fun `Parse_DailyAtTime_ReturnsDailyNullDays`() {
        val result = expectSuccess(parseScheduleGrammar("daily at 08:00"))

        assertNull(result.daysOfWeek)
        assertEquals(listOf("08:00"), result.times)
    }

    @Test
    fun `Parse_EveryDayAtTime_ReturnsDailyNullDays`() {
        val result = expectSuccess(parseScheduleGrammar("every day at 08:00"))

        assertNull(result.daysOfWeek)
        assertEquals(listOf("08:00"), result.times)
    }

    @Test
    fun `Parse_Daily_NoTime_ReturnsSuccessEmptyTimes`() {
        val result = expectSuccess(parseScheduleGrammar("daily"))

        assertNull(result.daysOfWeek)
        assertEquals(emptyList(), result.times)
    }

    @Test
    fun `Parse_TwelveHour_AmPm`() {
        assertEquals(listOf("08:00"), timesOf("daily at 8am"))
        assertEquals(listOf("20:30"), timesOf("daily at 8:30pm"))
        assertEquals(listOf("00:00"), timesOf("daily at 12am"))
        assertEquals(listOf("12:00"), timesOf("daily at 12pm"))
    }

    @Test
    fun `Parse_SpecificDays`() {
        val result = expectSuccess(parseScheduleGrammar("mon wed fri at 08:00"))

        assertEquals(listOf(1, 3, 5), result.daysOfWeek)
        assertEquals(1, result.times.size)
    }

    @Test
    fun `Parse_FullDayNames`() {
        val result = expectSuccess(parseScheduleGrammar("Monday Wednesday at 09:00"))

        assertEquals(listOf(1, 3), result.daysOfWeek)
    }

    @Test
    fun `Parse_LetterCombo_MWF`() {
        val result = expectSuccess(parseScheduleGrammar("MWF at 09:00"))

        assertEquals(listOf(1, 3, 5), result.daysOfWeek)
        assertEquals(1, result.times.size)
    }

    @Test
    fun `Parse_MultipleTimes`() {
        val result = expectSuccess(parseScheduleGrammar("daily at 08:00, 20:00"))

        assertNull(result.daysOfWeek)
        assertEquals(listOf("08:00", "20:00"), result.times)
    }

    @Test
    fun `Parse_Weekdays`() {
        val result = expectSuccess(parseScheduleGrammar("weekdays at 09:00"))

        assertEquals(listOf(1, 2, 3, 4, 5), result.daysOfWeek)
    }

    @Test
    fun `Parse_Weekends`() {
        val result = expectSuccess(parseScheduleGrammar("weekends at 10:00"))

        assertEquals(listOf(0, 6), result.daysOfWeek)
    }

    @Test
    fun `Parse_BareHour`() {
        assertEquals(listOf("08:00"), timesOf("daily at 8"))
    }

    @Test
    fun `Parse_AndSeparator`() {
        val result = expectSuccess(parseScheduleGrammar("daily at 08:00 and 20:00"))

        assertEquals(2, result.times.size)
    }

    @Test
    fun `Parse_UnknownTokens`() {
        val result = expectFailure(parseScheduleGrammar("daily at 08:00 nonsense"))

        assertContains(result.error, "nonsense")
        assertEquals(listOf("nonsense"), result.unparseableTokens)
    }

    @Test
    fun `IsGrammarToken_DayName_ReturnsTrue`() {
        assertTrue(isGrammarToken("Monday"))
        assertTrue(isGrammarToken("monday"))
        assertTrue(isGrammarToken("mon"))
    }

    @Test
    fun `IsGrammarToken_Keywords_ReturnsTrue`() {
        assertTrue(isGrammarToken("daily"))
        assertTrue(isGrammarToken("every"))
        assertTrue(isGrammarToken("at"))
        assertTrue(isGrammarToken("weekdays"))
    }

    @Test
    fun `IsGrammarToken_NonGrammarToken_ReturnsFalse`() {
        assertFalse(isGrammarToken("atorvastatin"))
        assertFalse(isGrammarToken("20mg"))
        assertFalse(isGrammarToken(""))
    }
}

class ParseScheduleGrammarInputHandlingTest {
    @Test
    fun `rejects a missing input`() {
        // The TypeScript also passes `undefined`; Kotlin has only `null` to be absent.
        for (input in listOf(null, "   \t ")) {
            val result = expectFailure(parseScheduleGrammar(input))
            assertEquals("No schedule text provided.", result.error)
            assertEquals(emptyList(), result.unparseableTokens)
        }
    }

    @Test
    fun `accepts tabs and commas as separators`() {
        val result = expectSuccess(parseScheduleGrammar("mon,\twed\tat\t08:00,20:00"))

        assertEquals(listOf(1, 3), result.daysOfWeek)
        assertEquals(listOf("08:00", "20:00"), result.times)
    }

    @Test
    fun `de-duplicates and sorts days and times`() {
        val result = expectSuccess(parseScheduleGrammar("fri mon fri at 20:00, 8am, 08:00"))

        assertEquals(listOf(1, 5), result.daysOfWeek)
        assertEquals(listOf("08:00", "20:00"), result.times)
    }

    @Test
    fun `reports every unparseable token, in input order`() {
        val result = expectFailure(parseScheduleGrammar("daily at 08:00 with food"))

        assertEquals(listOf("with", "food"), result.unparseableTokens)
        assertContains(result.error, "with, food")
    }

    @Test
    fun `fails when nothing at all was recognized`() {
        val result = expectFailure(parseScheduleGrammar("at and"))

        assertEquals("Couldn't parse the schedule. Try 'daily at 08:00' or 'MWF at 09:00'.", result.error)
        assertEquals(listOf("at", "and"), result.unparseableTokens)
    }

    @Test
    fun `treats times without a day spec as daily`() {
        val result = expectSuccess(parseScheduleGrammar("08:00"))

        assertNull(result.daysOfWeek)
        assertEquals(listOf("08:00"), result.times)
    }

    @Test
    fun `accepts a day spec with no times`() {
        val result = expectSuccess(parseScheduleGrammar("weekends"))

        assertEquals(listOf(0, 6), result.daysOfWeek)
        assertEquals(emptyList(), result.times)
    }

    @Test
    fun `quotes the unparseable tokens in the error with the full hint`() {
        val result = expectFailure(parseScheduleGrammar("daily at 08:00 nonsense"))

        assertEquals(
            "Couldn't parse: nonsense. Use a day spec (daily/weekdays/weekends/Mon…/MWF) and times like 08:00 or 8am.",
            result.error,
        )
    }
}

class ParseScheduleGrammarDaySpecsTest {
    @Test
    fun `accepts every day-name spelling`() {
        val cases =
            listOf(
                "sun" to 0,
                "sunday" to 0,
                "mon" to 1,
                "monday" to 1,
                "tue" to 2,
                "tues" to 2,
                "tuesday" to 2,
                "wed" to 3,
                "wednesday" to 3,
                "thu" to 4,
                "thur" to 4,
                "thurs" to 4,
                "thursday" to 4,
                "fri" to 5,
                "friday" to 5,
                "sat" to 6,
                "saturday" to 6,
            )

        for ((name, day) in cases) {
            assertEquals(listOf(day), expectSuccess(parseScheduleGrammar(name)).daysOfWeek)
        }
    }

    @Test
    fun `prefers two-character codes over one-character ones`() {
        assertEquals(listOf(2, 4), expectSuccess(parseScheduleGrammar("tuth")).daysOfWeek)
        assertEquals(listOf(0, 6), expectSuccess(parseScheduleGrammar("sasu")).daysOfWeek)
        assertEquals(listOf(1, 2, 3, 4, 5), expectSuccess(parseScheduleGrammar("mtuwthf")).daysOfWeek)
    }

    /**
     * Both C# doc comments advertise `MTWTF`, but `OneCharCodes` holds only
     * `m`/`w`/`f` — a bare `t` is ambiguous between Tue and Thu, so the combo
     * dies on the second character. The behaviour is ported as-is; this pins it
     * so the gap between the docs and the code stays visible.
     */
    @Test
    fun `rejects MTWTF, which the C# documents but cannot parse`() {
        val result = expectFailure(parseScheduleGrammar("mtwtf at 09:00"))

        assertEquals(listOf("mtwtf"), result.unparseableTokens)
    }

    @Test
    fun `rejects a combo it cannot consume to the end`() {
        val result = expectFailure(parseScheduleGrammar("tux at 09:00"))

        assertEquals(listOf("tux"), result.unparseableTokens)
    }

    @Test
    fun `treats a bare day as a day spec`() {
        val result = expectSuccess(parseScheduleGrammar("day at 09:00"))

        assertNull(result.daysOfWeek)
        assertEquals(listOf("09:00"), result.times)
    }
}

class ParseScheduleGrammarTimesTest {
    @Test
    fun `accepts dotted am-pm suffixes`() {
        assertEquals(listOf("08:00"), timesOf("daily at 8a.m."))
        assertEquals(listOf("21:15"), timesOf("daily at 9:15p.m."))
    }

    @Test
    fun `accepts 24-hour times with one- or two-digit parts`() {
        assertEquals(listOf("08:05"), timesOf("daily at 8:05"))
        assertEquals(listOf("08:05"), timesOf("daily at 8:5"))
        assertEquals(listOf("23:59"), timesOf("daily at 23:59"))
        assertEquals(listOf("00:00"), timesOf("daily at 00:00"))
    }

    @Test
    fun `accepts bare hours across the whole day`() {
        assertEquals(listOf("00:00"), timesOf("daily at 0"))
        assertEquals(listOf("20:00"), timesOf("daily at 20"))
        assertEquals(listOf("08:00"), timesOf("daily at +8"))
    }

    /**
     * `isPm && hour < 12` only ever *adds* twelve, so a pm token whose hour is
     * already past noon is taken verbatim rather than rejected. Ported as-is.
     */
    @Test
    fun `passes through pm hours that are already 24-hour`() {
        assertEquals(listOf("13:00"), timesOf("daily at 13pm"))
        assertEquals(listOf("23:00"), timesOf("daily at 23pm"))
    }

    @Test
    fun `rejects out-of-range times`() {
        for (token in listOf("25pm", "8:99am", "25:00", "08:75", "24", "-1")) {
            val result = expectFailure(parseScheduleGrammar("daily at $token"))
            assertEquals(listOf(token), result.unparseableTokens)
        }
    }

    // Not in the vitest suite (which `minute <= 60` survives): the last valid
    // minute and the first invalid one, on the 12-hour and the 24-hour forms.
    @Test
    fun `accepts minute 59 and rejects minute 60 on both clock forms`() {
        assertEquals(listOf("20:59"), timesOf("daily at 8:59pm"))
        assertEquals(listOf("23:59"), timesOf("daily at 23:59"))
        for (token in listOf("8:60am", "23:60", "24:00", "24pm")) {
            assertEquals(listOf(token), expectFailure(parseScheduleGrammar("daily at $token")).unparseableTokens)
        }
    }

    // Not in the vitest suite: JS parses an enormous bare hour to a double and
    // rejects it by range, whereas an Int parse has to notice the overflow.
    @Test
    fun `rejects a bare hour too large for an Int`() {
        val result = expectFailure(parseScheduleGrammar("daily at 99999999999"))

        assertEquals(listOf("99999999999"), result.unparseableTokens)
    }
}

class IsGrammarTokenTest {
    @Test
    fun `accepts the remaining keywords`() {
        assertTrue(isGrammarToken("and"))
        assertTrue(isGrammarToken("day"))
        assertTrue(isGrammarToken("weekends"))
    }

    @Test
    fun `accepts letter combos and times`() {
        assertTrue(isGrammarToken("MWF"))
        assertTrue(isGrammarToken("TuTh"))
        assertTrue(isGrammarToken("08:00"))
        assertTrue(isGrammarToken("8pm"))
        assertTrue(isGrammarToken(" 8 "))
    }

    @Test
    fun `rejects free text, dosages, and whitespace`() {
        assertFalse(isGrammarToken("lisinopril"))
        assertFalse(isGrammarToken("10mg"))
        assertFalse(isGrammarToken("   "))
    }
}

// Not in the vitest suite, but pinned against what V8 does: JS `trim()` and the
// regex `\s` use the ECMAScript whitespace set, which is not Java's.
class ScheduleGrammarJsWhitespaceTest {
    @Test
    fun `a no-break space between a time and its suffix is whitespace`() {
        assertEquals(listOf("08:00"), timesOf("daily at 8\u00A0am"))
    }

    @Test
    fun `a byte order mark alone is blank input`() {
        assertEquals("No schedule text provided.", expectFailure(parseScheduleGrammar("\uFEFF")).error)
    }

    @Test
    fun `a unit separator is not whitespace`() {
        assertEquals(listOf("\u001F"), expectFailure(parseScheduleGrammar("\u001F")).unparseableTokens)
    }

    @Test
    fun `a byte order mark around a token is stripped`() {
        assertEquals(listOf("08:00"), timesOf("\uFEFF08:00\uFEFF"))
        assertTrue(isGrammarToken("\uFEFF8"))
        assertFalse(isGrammarToken("\uFEFF"))
    }
}
