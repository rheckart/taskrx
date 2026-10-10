package com.taskrx.core.refill

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.weeklyTimes
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Local wall-clock helper, matching the pattern in `OccurrencesTest`. */
internal fun local(
    year: Int,
    month: Int,
    day: Int,
    hour: Int = 0,
    minute: Int = 0,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

/** An hour before the first dose of the day, so today's dose is still to come. */
internal val NOW: Long = local(2026, 8, 13, 8)

private val REFILL_OPTIONS = PlanRefillOptions(refillReminderId = "refill-reminder-1", now = NOW)

private fun buildMedication(
    name: String = "Atorvastatin",
    schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
    pillsRemaining: Double? = null,
    pillsPerDose: Double? = null,
    refillReminderDays: Int? = null,
    kind: ReminderKind = ReminderKind.Medication,
): Reminder =
    createReminder(
        id = "med-1",
        userId = "user-1",
        createdAt = 0,
        updatedAt = 0,
        kind = kind,
        name = name,
        schedule = schedule,
        pillsRemaining = pillsRemaining,
        pillsPerDose = pillsPerDose,
        refillReminderDays = refillReminderDays,
    )

private fun needed(decision: RefillDecision): Reminder {
    assertIs<RefillDecision.Needed>(decision)
    return decision.refillReminder
}

/**
 * `ProjectionTest` runs every rule about WHEN a refill is due; these cases are
 * about WHAT is raised when one is, and about the decision reaching this
 * function unchanged rather than being made a second time here.
 *
 * The original suite was translated 1:1 from `RefillPlannerTests.cs`, whose
 * cases all pinned the `dosesInLookaheadWindow * 3` threshold. Issue #17
 * replaced that threshold - it compares pills to doses and so ignores
 * `pillsPerDose`, which the ticket forbids - so those cases now state the rule
 * that replaced it, against the projection, in `ProjectionTest`.
 */
class PlannerTest {
    @Test
    fun `planRefill raises a Refill Reminder when the supply runs out inside the warning window`() {
        val med = buildMedication(
            name = "Atorvastatin",
            pillsRemaining = 5.0,
            pillsPerDose = 1.0,
            refillReminderDays = 7,
        )

        val reminder = needed(planRefill(med, REFILL_OPTIONS))

        assertEquals("Refill Atorvastatin", reminder.name)
        assertEquals(med.userId, reminder.userId)
        assertEquals(true, reminder.active)
    }

    @Test
    fun `planRefill raises nothing while the supply outlasts the warning window`() {
        val med = buildMedication(pillsRemaining = 100.0, pillsPerDose = 1.0, refillReminderDays = 7)

        assertEquals(RefillDecision.NoRefill, planRefill(med, REFILL_OPTIONS))
    }

    @Test
    fun `planRefill raises nothing for a reminder with no supply to run out of`() {
        val med = buildMedication(pillsPerDose = 1.0)

        assertEquals(RefillDecision.NoRefill, planRefill(med, REFILL_OPTIONS))
    }

    @Test
    fun `planRefill raises nothing for a task, whatever numbers it carries`() {
        val task = buildMedication(kind = ReminderKind.Task, pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(RefillDecision.NoRefill, planRefill(task, REFILL_OPTIONS))
    }

    /**
     * Collecting a prescription is an errand, and ADR 0003 keeps tasks out of the
     * bounded medication Horizon - so raising a refill can never cost a dose its
     * alarm slot. A Refill Reminder that was itself a `medication` would compete
     * with the medication it is about.
     */
    @Test
    fun `planRefill raises a task rather than a second medication`() {
        val med = buildMedication(pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(ReminderKind.Task, needed(planRefill(med, REFILL_OPTIONS)).kind)
    }

    /**
     * The C# built this with a daily schedule carrying no times, because the
     * `none` variant did not exist yet. That is the misconfiguration `none`
     * replaced: it yields no Occurrences while claiming a daily schedule, so the
     * raised Reminder would appear on neither the timeline nor the Backlog - a
     * refill warning nobody can be shown is not a warning.
     */
    @Test
    fun `planRefill raises it into the Backlog, which is where an errand with no moment lives`() {
        val med = buildMedication(pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(noneTimes(), needed(planRefill(med, REFILL_OPTIONS)).schedule)
    }

    @Test
    fun `planRefill raises it at high priority, above the ordinary errand`() {
        val med = buildMedication(pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(Priority.High, needed(planRefill(med, REFILL_OPTIONS)).priority)
    }

    @Test
    fun `planRefill stamps it with the caller's id and instant rather than inventing either`() {
        val med = buildMedication(name = "Lisinopril", pillsRemaining = 0.0, pillsPerDose = 1.0)
        val options = PlanRefillOptions(refillReminderId = "generated-refill-id", now = NOW)

        val reminder = needed(planRefill(med, options))

        assertEquals("generated-refill-id", reminder.id)
        assertEquals(NOW, reminder.createdAt)
        assertEquals(NOW, reminder.updatedAt)
        assertEquals(emptyList(), reminder.tags)
        assertEquals(emptyList(), reminder.caregiverIds)
    }

    /**
     * The supply is judged at `options.now`, not at some other instant. An empty
     * bottle for a Monday medication is four days away from mattering on the
     * Thursday before, and is due on the Monday itself.
     */
    @Test
    fun `planRefill judges the supply at the instant it was asked about`() {
        val med = buildMedication(
            schedule = weeklyTimes(listOf(1), listOf("09:00")),
            pillsRemaining = 0.0,
            pillsPerDose = 1.0,
            refillReminderDays = 0,
        )

        // Thursday 2026-08-13.
        assertEquals(RefillDecision.NoRefill, planRefill(med, PlanRefillOptions("r", NOW)))
        // The Monday after.
        assertTrue(planRefill(med, PlanRefillOptions("r", local(2026, 8, 17, 8))) is RefillDecision.Needed)
    }

    @Test
    fun `planRefill raises nothing for a medication that never comes due`() {
        val med = buildMedication(schedule = noneTimes(), pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(RefillDecision.NoRefill, planRefill(med, REFILL_OPTIONS))
    }

    /**
     * The name is the only handle a caller has on a Refill Reminder it raised
     * earlier, and the raise has to be idempotent - a screen that can be opened
     * twice must not leave two. So the raiser and the "is one already standing?"
     * check must produce the same string, which is why it is a function and not
     * a template written out at each end.
     */
    @Test
    fun `refillReminderName names the Reminder the raiser will build`() {
        val med = buildMedication(name = "Lisinopril", pillsRemaining = 0.0, pillsPerDose = 1.0)

        assertEquals(refillReminderName(med), needed(planRefill(med, REFILL_OPTIONS)).name)
    }
}
