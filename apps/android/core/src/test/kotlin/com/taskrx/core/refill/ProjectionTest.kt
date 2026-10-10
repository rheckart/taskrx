package com.taskrx.core.refill

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ONCE_A_DAY = dailyTimes(listOf("09:00"))

private val NEW_YORK: ZoneId = ZoneId.of("America/New_York")

private fun buildMedication(
    schedule: ScheduleTimes = ONCE_A_DAY,
    pillsRemaining: Double? = null,
    pillsPerDose: Double? = null,
    refillReminderDays: Int? = null,
    kind: ReminderKind = ReminderKind.Medication,
    active: Boolean = true,
): Reminder =
    createReminder(
        id = "med-1",
        userId = "user-1",
        createdAt = 0,
        updatedAt = 0,
        kind = kind,
        name = "Atorvastatin",
        schedule = schedule,
        pillsRemaining = pillsRemaining,
        pillsPerDose = pillsPerDose,
        refillReminderDays = refillReminderDays,
        active = active,
    )

/**
 * Narrows to the projected variant so a case can read its fields.
 *
 * A failed narrowing is a failed assertion rather than a thrown null three
 * lines later, which is what makes the "why is there no projection" cases
 * readable when one of them regresses.
 */
private fun outlook(
    reminder: Reminder,
    now: Long = NOW,
    zone: ZoneId = ZoneId.systemDefault(),
): RefillProjection.RefillOutlook {
    val projection = projectRefill(reminder, now, zone)
    if (projection !is RefillProjection.RefillOutlook) {
        throw AssertionError("expected a projection, got $projection")
    }
    return projection
}

/**
 * How long a finite supply lasts.
 *
 * The rule these cases state, and the one `planRefill`'s ported threshold could
 * not: the supply is spent an Occurrence at a time, and each Occurrence costs
 * `pillsPerDose` - so what matters is the Schedule the Reminder actually keeps,
 * not the number of pills alone.
 */
class ProjectionSupplyTest {
    @Test
    fun `projectRefill - how long the supply lasts - spends one dose per Occurrence, so thirty daily pills last thirty days`() {
        val med = buildMedication(pillsRemaining = 30.0, pillsPerDose = 1.0)

        assertEquals(30, outlook(med).occurrencesRemaining)
        assertEquals(30, outlook(med).daysRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - counts what is taken each time, so the same thirty taken two at a time last half as long`() {
        val med = buildMedication(pillsRemaining = 30.0, pillsPerDose = 2.0)

        assertEquals(15, outlook(med).occurrencesRemaining)
        assertEquals(15, outlook(med).daysRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - counts how often they are taken, so twice a day halves the days again`() {
        val med = buildMedication(
            schedule = dailyTimes(listOf("09:00", "21:00")),
            pillsRemaining = 30.0,
            pillsPerDose = 1.0,
        )

        assertEquals(30, outlook(med).occurrencesRemaining)
        assertEquals(15, outlook(med).daysRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - follows a weekly schedule's own days rather than assuming a daily one`() {
        // Thursday 2026-08-13. Mondays, Wednesdays and Fridays: six doses reach the
        // Wednesday a fortnight out, and the seventh - the one there is nothing for
        // - is the Friday after. Six daily pills would have run out on the 19th.
        val med = buildMedication(
            schedule = weeklyTimes(listOf(1, 3, 5), listOf("09:00")),
            pillsRemaining = 6.0,
            pillsPerDose = 1.0,
        )

        assertEquals(local(2026, 8, 28, 9), outlook(med).runsOutAt)
    }

    @Test
    fun `projectRefill - how long the supply lasts - does not count a leftover pill that cannot make a whole dose`() {
        val med = buildMedication(pillsRemaining = 5.0, pillsPerDose = 2.0)

        assertEquals(2, outlook(med).occurrencesRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - runs out at the first Occurrence the supply cannot cover`() {
        val med = buildMedication(pillsRemaining = 3.0, pillsPerDose = 1.0)

        // Today's, tomorrow's and the next are covered; the fourth is not.
        assertEquals(local(2026, 8, 16, 9), outlook(med).runsOutAt)
        assertEquals(3, outlook(med).daysRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - says a supply already spent runs out at the very next dose`() {
        val med = buildMedication(pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(0, outlook(med).occurrencesRemaining)
        assertEquals(local(2026, 8, 13, 9), outlook(med).runsOutAt)
        assertEquals(0, outlook(med).daysRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - treats a supply recorded as less than nothing as nothing`() {
        val med = buildMedication(pillsRemaining = -5.0, pillsPerDose = 1.0)

        assertEquals(0, outlook(med).occurrencesRemaining)
    }

    @Test
    fun `projectRefill - how long the supply lasts - answers longer than it can see for a supply too large to count`() {
        // TypeScript's doubles reach Infinity here; a Long that wrapped would ask for a negative count.
        val med = buildMedication(pillsRemaining = 1e300, pillsPerDose = 1e-10)

        assertNull(outlook(med).runsOutAt)
        assertFalse(outlook(med).needsRefill)
    }

    /**
     * A Reminder with no Schedule produces no Occurrences at all, so its supply
     * is never spent. Reporting a run-out date for it would be inventing one.
     */
    @Test
    fun `projectRefill - how long the supply lasts - has no run-out date for a medication that never comes due`() {
        val med = buildMedication(schedule = noneTimes(), pillsRemaining = 1.0, pillsPerDose = 1.0)

        assertNull(outlook(med).runsOutAt)
        assertNull(outlook(med).daysRemaining)
        assertFalse(outlook(med).needsRefill)
    }

    @Test
    fun `projectRefill - how long the supply lasts - reports no date rather than a wrong one when the supply outlasts the schedule horizon`() {
        // The daily engine walks 60 days; two hundred doses are well past it.
        val med = buildMedication(pillsRemaining = 200.0, pillsPerDose = 1.0)

        assertEquals(200, outlook(med).occurrencesRemaining)
        assertNull(outlook(med).runsOutAt)
        assertFalse(outlook(med).needsRefill)
    }

    /**
     * `interval` schedules have no horizon of their own - they return exactly as
     * many Occurrences as they are asked for - so the projection has to stop
     * itself. It stops by answering "further off than I can see", never by
     * answering a nearer date than the truth.
     */
    @Test
    fun `projectRefill - how long the supply lasts - stops walking an endless schedule rather than projecting a year of hourly doses`() {
        val med = buildMedication(schedule = intervalTimes(1, NOW), pillsRemaining = 5000.0, pillsPerDose = 1.0)

        assertNull(outlook(med).runsOutAt)
    }

    @Test
    fun `projectRefill - how long the supply lasts - still projects an interval supply that is within reach`() {
        val med = buildMedication(schedule = intervalTimes(6, NOW), pillsRemaining = 12.0, pillsPerDose = 1.0)

        // Twelve doses six hours apart: the thirteenth is three days out.
        assertEquals(3, outlook(med).daysRemaining)
    }
}

/**
 * When the warning is raised.
 *
 * The window is the Reminder's own - the acceptance criterion says the refill is
 * raised "according to the warning window on that Reminder" - so these cases
 * move the window and the supply independently and assert the verdict flips
 * where the two meet.
 */
class ProjectionWarningWindowTest {
    @Test
    fun `projectRefill - the warning window - raises a refill when the supply runs out inside the window`() {
        val med = buildMedication(pillsRemaining = 7.0, pillsPerDose = 1.0, refillReminderDays = 7)

        assertTrue(outlook(med).needsRefill)
    }

    @Test
    fun `projectRefill - the warning window - does not raise one while the supply outlasts the window`() {
        val med = buildMedication(pillsRemaining = 8.0, pillsPerDose = 1.0, refillReminderDays = 7)

        assertFalse(outlook(med).needsRefill)
    }

    @Test
    fun `projectRefill - the warning window - raises earlier for someone who asked for more warning`() {
        val med = buildMedication(pillsRemaining = 8.0, pillsPerDose = 1.0, refillReminderDays = 14)

        assertTrue(outlook(med).needsRefill)
    }

    @Test
    fun `projectRefill - the warning window - raises on the strength of the doses left, not the pills left`() {
        // Sixteen pills is twice the eight that would sit outside a seven-day
        // window - but taken two at a time they are eight doses, so they do not.
        val med = buildMedication(pillsRemaining = 16.0, pillsPerDose = 2.0, refillReminderDays = 7)

        assertFalse(outlook(med).needsRefill)

        val takenFour = buildMedication(pillsRemaining = 16.0, pillsPerDose = 4.0, refillReminderDays = 7)

        assertTrue(outlook(takenFour).needsRefill)
    }

    @Test
    fun `projectRefill - the warning window - gives a Reminder that named no window the default one`() {
        val med = buildMedication(pillsRemaining = 7.0, pillsPerDose = 1.0)

        assertEquals(DEFAULT_REFILL_REMINDER_DAYS, outlook(med).warningWindowDays)
        assertTrue(outlook(med).needsRefill)
    }

    @Test
    fun `projectRefill - the warning window - reports the window it judged against, so the surface can say so`() {
        val med = buildMedication(pillsRemaining = 30.0, pillsPerDose = 1.0, refillReminderDays = 3)

        assertEquals(3, outlook(med).warningWindowDays)
    }

    @Test
    fun `projectRefill - the warning window - honours a window of nothing by warning only on the day itself`() {
        val runsOutToday = buildMedication(pillsRemaining = 0.0, pillsPerDose = 1.0, refillReminderDays = 0)
        val runsOutTomorrow = buildMedication(pillsRemaining = 1.0, pillsPerDose = 1.0, refillReminderDays = 0)

        assertTrue(outlook(runsOutToday).needsRefill)
        assertFalse(outlook(runsOutTomorrow).needsRefill)
    }
}

/**
 * What has no projection, and why each is a different answer.
 *
 * The acceptance criterion is that a medication with no recorded supply shows
 * "no refill information rather than a misleading zero" - so the absence has to
 * be a value the surface can render as a sentence, not a `0` it cannot tell
 * apart from an empty bottle.
 */
class ProjectionUnprojectableTest {
    @Test
    fun `projectRefill - what cannot be projected - has nothing to say about a task`() {
        val task = buildMedication(kind = ReminderKind.Task, pillsRemaining = 5.0, pillsPerDose = 1.0)

        assertEquals(
            RefillProjection.Unprojectable(UnprojectableReason.NotAMedication),
            projectRefill(task, NOW),
        )
    }

    @Test
    fun `projectRefill - what cannot be projected - has nothing to say about a medication that was stopped`() {
        val med = buildMedication(pillsRemaining = 1.0, pillsPerDose = 1.0, active = false)

        assertEquals(RefillProjection.Unprojectable(UnprojectableReason.Stopped), projectRefill(med, NOW))
    }

    @Test
    fun `projectRefill - what cannot be projected - distinguishes an unrecorded supply from an empty one`() {
        val unrecorded = buildMedication(pillsPerDose = 1.0)

        assertEquals(RefillProjection.Unprojectable(UnprojectableReason.NoSupply), projectRefill(unrecorded, NOW))

        val empty = buildMedication(pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(0, outlook(empty).occurrencesRemaining)
    }

    @Test
    fun `projectRefill - what cannot be projected - cannot project a supply without knowing how many are taken at a time`() {
        val med = buildMedication(pillsRemaining = 30.0)

        assertEquals(RefillProjection.Unprojectable(UnprojectableReason.NoDoseSize), projectRefill(med, NOW))
    }

    @Test
    fun `projectRefill - what cannot be projected - treats a dose of zero pills as no dose size at all`() {
        // Otherwise the division is an infinite supply, and a medication that never
        // runs out is the one reading this feature exists to prevent.
        val med = buildMedication(pillsRemaining = 30.0, pillsPerDose = 0.0)

        assertEquals(RefillProjection.Unprojectable(UnprojectableReason.NoDoseSize), projectRefill(med, NOW))
    }

    @Test
    fun `unprojectable reasons keep the spelling the TypeScript original used`() {
        assertEquals(
            listOf("not-a-medication", "stopped", "no-supply", "no-dose-size"),
            UnprojectableReason.entries.map { it.wire },
        )
    }
}

/**
 * Days remaining is a count of local calendar days, and the suite otherwise runs
 * at `TZ=UTC` where every day is 24 hours. A projection built on fixed 24-hour
 * blocks is a day out either side of a clock change - which is a refill warning
 * that arrives the day after the pills ran out.
 */
class ProjectionDaylightSavingTest {
    @Test
    fun `projectRefill - daylight saving - counts three days across spring forward, not two`() {
        // 2027-03-14 is the US spring-forward date.
        val med = buildMedication(pillsRemaining = 3.0, pillsPerDose = 1.0)

        assertEquals(3, outlook(med, local(2027, 3, 13, 8, zone = NEW_YORK), NEW_YORK).daysRemaining)
    }

    @Test
    fun `projectRefill - daylight saving - counts three days across fall back, not four`() {
        // 2027-11-07 is the US fall-back date.
        val med = buildMedication(pillsRemaining = 3.0, pillsPerDose = 1.0)

        assertEquals(3, outlook(med, local(2027, 11, 6, 8, zone = NEW_YORK), NEW_YORK).daysRemaining)
    }
}
