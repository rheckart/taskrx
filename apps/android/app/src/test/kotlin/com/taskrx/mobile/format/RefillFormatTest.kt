package com.taskrx.mobile.format

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.refill.projectRefill
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the Supplies surface says, given what the projection found. Mirrors refill.test.ts.
 *
 * These run against real `projectRefill` output rather than hand-built objects, so a case
 * cannot state a combination the projection never produces. Each rule is a sentence a person
 * reads about their own medication, and the wrong sentence over the right number is the
 * failure that matters.
 */
@RunWith(RobolectricTestRunner::class)
class RefillFormatTest {
    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US

    private fun local(y: Int, m: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    /** An hour before the first dose of the day, so today's is still to come. */
    private val now = local(2026, 8, 13, 8)

    private fun medication(
        schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
        pillsRemaining: Double? = null,
        pillsPerDose: Double? = null,
        refillReminderDays: Int? = null,
    ): Reminder =
        createReminder(
            id = "med-1",
            userId = "user-1",
            createdAt = 0,
            updatedAt = 0,
            kind = ReminderKind.Medication,
            name = "Atorvastatin",
            schedule = schedule,
            pillsRemaining = pillsRemaining,
            pillsPerDose = pillsPerDose,
            refillReminderDays = refillReminderDays,
        )

    private fun headline(med: Reminder) = refillHeadline(med, projectRefill(med, now, zone))

    private fun detail(med: Reminder) = refillDetail(med, projectRefill(med, now, zone), zone, locale)

    private fun urgency(med: Reminder) = refillUrgency(projectRefill(med, now, zone))

    @Test
    fun `counts the days when there are days to count`() {
        assertEquals("12 days left", headline(medication(pillsRemaining = 12.0, pillsPerDose = 1.0)))
    }

    @Test
    fun `says one day rather than 1 days`() {
        assertEquals("1 day left", headline(medication(pillsRemaining = 1.0, pillsPerDose = 1.0)))
    }

    @Test
    fun `says today for a supply already spent`() {
        assertEquals("Runs out today", headline(medication(pillsRemaining = 0.0, pillsPerDose = 1.0)))
    }

    /**
     * "a medication with no recorded supply shows no refill information rather than a
     * misleading zero". "0 days left" and "Supply not tracked" are opposite facts, and on
     * this surface the wrong one is an alarm about a medication that is fine.
     */
    @Test
    fun `never turns an unrecorded supply into a number`() {
        val unrecorded = medication(pillsPerDose = 1.0)

        assertEquals("Supply not tracked", headline(unrecorded))
        assertFalse(headline(unrecorded).any { it.isDigit() })
        assertEquals("Add how many you have to see how long it lasts.", detail(unrecorded))
    }

    @Test
    fun `asks for the dose size when that is the missing half`() {
        assertEquals("Add how many you take at a time to see how long it lasts.", detail(medication(pillsRemaining = 30.0)))
    }

    @Test
    fun `calls a supply beyond the walk plenty`() {
        // Two hundred daily doses are past the schedule engine's 60-day walk.
        assertEquals("Plenty left", headline(medication(pillsRemaining = 200.0, pillsPerDose = 1.0)))
    }

    /**
     * The same missing date, the opposite news. A Reminder with no times set has no run-out
     * date because nothing is being taken: "Plenty left" over an empty bottle would be the
     * misleading reading arriving by another route.
     */
    @Test
    fun `does not call an empty bottle plenty just because nothing is scheduled`() {
        val unscheduled = medication(schedule = noneTimes(), pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals("No times set", headline(unscheduled))
        assertEquals("nothing left, and no times set — so none are being used up", detail(unscheduled))
    }

    @Test
    fun `says a stock beyond the walk is further off than this can project`() {
        assertEquals("enough for 200 more, further off than this can project", detail(medication(pillsRemaining = 200.0, pillsPerDose = 1.0)))
    }

    @Test
    fun `explains a stopped medication and a task in words`() {
        val stopped = medication(pillsRemaining = 5.0, pillsPerDose = 1.0).copy(active = false)
        assertEquals("This medication is stopped, so nothing is being used up.", detail(stopped))

        val task = medication(pillsRemaining = 5.0, pillsPerDose = 1.0).copy(kind = ReminderKind.Task)
        assertEquals("Only medications have a supply.", detail(task))
    }

    /**
     * CONTEXT.md defines a Dose as the quantity taken at one time and says to avoid the word
     * for a scheduled taking, which is what a count of Occurrences is.
     */
    @Test
    fun `counts what is left without calling them doses`() {
        val text = detail(medication(pillsRemaining = 12.0, pillsPerDose = 1.0))

        assertTrue(text.startsWith("enough for 12 more, runs out "), text)
        assertFalse(text.contains("dose", ignoreCase = true))
    }

    @Test
    fun `says one more rather than 1 mores`() {
        assertTrue(detail(medication(pillsRemaining = 1.0, pillsPerDose = 1.0)).startsWith("enough for 1 more, runs out "))
    }

    @Test
    fun `shows the working, so a changed dose size can be checked against it`() {
        // Thirty pills taken three at a time is ten takings, not thirty.
        assertTrue(detail(medication(pillsRemaining = 30.0, pillsPerDose = 3.0)).startsWith("enough for 10 more, "))
    }

    @Test
    fun `names the day the supply runs out`() {
        assertEquals(
            "enough for 3 more, runs out ${formatDay(local(2026, 8, 16, 9), zone, locale)}",
            detail(medication(pillsRemaining = 3.0, pillsPerDose = 1.0)),
        )
    }

    /** An empty bottle has not "run out on" a future date; the date is when the next one is wanted. */
    @Test
    fun `tells an empty bottle when the next one is due, not when it runs out`() {
        assertEquals(
            "nothing left — the next is due ${formatDay(local(2026, 8, 13, 9), zone, locale)}",
            detail(medication(pillsRemaining = 0.0, pillsPerDose = 1.0)),
        )
    }

    @Test
    fun `says nothing about a medication that is fine`() {
        assertEquals(RefillUrgency.None, urgency(medication(pillsRemaining = 40.0, pillsPerDose = 1.0, refillReminderDays = 7)))
    }

    @Test
    fun `says nothing about a supply nobody recorded`() {
        assertEquals(RefillUrgency.None, urgency(medication()))
    }

    @Test
    fun `asks for a reorder while there is still something left`() {
        assertEquals(RefillUrgency.Reorder, urgency(medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)))
    }

    /** Louder only once there is genuinely none left, so the strongest word keeps meaning something. */
    @Test
    fun `is louder once there is none left at all`() {
        assertEquals(RefillUrgency.Out, urgency(medication(pillsRemaining = 0.0, pillsPerDose = 1.0, refillReminderDays = 7)))
    }
}
