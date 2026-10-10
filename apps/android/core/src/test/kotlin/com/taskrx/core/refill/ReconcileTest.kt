package com.taskrx.core.refill

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import kotlin.test.Test
import kotlin.test.assertEquals

private fun medication(
    id: String = "med-1",
    name: String = "Atorvastatin",
    schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
    pillsRemaining: Double? = null,
    pillsPerDose: Double? = null,
    refillReminderDays: Int? = null,
    active: Boolean = true,
): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        createdAt = 0,
        updatedAt = 0,
        kind = ReminderKind.Medication,
        name = name,
        schedule = schedule,
        pillsRemaining = pillsRemaining,
        pillsPerDose = pillsPerDose,
        refillReminderDays = refillReminderDays,
        active = active,
    )

private fun standingRefill(id: String, name: String): Reminder =
    createReminder(
        id = id,
        userId = "user-1",
        createdAt = 0,
        updatedAt = 0,
        kind = ReminderKind.Task,
        name = name,
        schedule = noneTimes(),
    )

/** Ids in the order they are asked for, so a raise can be identified. */
private fun idMinter(): () -> String {
    var next = 0
    return {
        next += 1
        "minted-$next"
    }
}

private fun reconcile(
    medications: List<Reminder>,
    standing: List<Reminder> = emptyList(),
): RefillReconciliation =
    reconcileRefills(
        ReconcileRefillsInput(medications = medications, standing = standing, now = NOW, mintId = idMinter()),
    )

/** A medication whose supply runs out well inside a seven-day window. */
private fun runningLow(name: String, id: String = "med-1"): Reminder =
    medication(id = id, name = name, pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)

class ReconcileRaisingTest {
    @Test
    fun `reconcileRefills - raising - raises a Refill Reminder for a medication running out inside its window`() {
        val result = reconcile(medications = listOf(runningLow("Atorvastatin")))

        assertEquals(listOf("Refill Atorvastatin"), result.raise.map { it.name })
    }

    @Test
    fun `reconcileRefills - raising - raises nothing while the supply outlasts the window`() {
        val comfortable = medication(pillsRemaining = 40.0, pillsPerDose = 1.0, refillReminderDays = 7)

        assertEquals(emptyList(), reconcile(medications = listOf(comfortable)).raise)
    }

    /**
     * The surface this feeds can be opened as many times as somebody likes. Two
     * Refill Reminders for one medication is the app nagging about a thing it
     * already said, and it compounds - a week of glances is a week of duplicates.
     */
    @Test
    fun `reconcileRefills - raising - raises nothing for a medication already warned about`() {
        val result = reconcile(
            medications = listOf(runningLow("Atorvastatin")),
            standing = listOf(standingRefill("task-1", "Refill Atorvastatin")),
        )

        assertEquals(emptyList(), result.raise)
        assertEquals(emptyList(), result.retire)
    }

    /**
     * Two medications entered under one name - a duplicate, most likely - must not
     * produce two identical Refill Reminders in a single pass. The name is the
     * only handle there is on a raised Reminder, so a second one is a warning
     * nothing afterwards can tell apart from the first, including the check that
     * is meant to stop a third.
     */
    @Test
    fun `reconcileRefills - raising - raises one Reminder for two medications sharing a name`() {
        val result = reconcile(
            medications = listOf(runningLow("Atorvastatin", "med-1"), runningLow("Atorvastatin", "med-2")),
        )

        assertEquals(1, result.raise.size)
    }

    @Test
    fun `reconcileRefills - raising - raises nothing for a task, whatever numbers it carries`() {
        val task = createReminder(
            id = "task-1",
            userId = "user-1",
            createdAt = 0,
            updatedAt = 0,
            kind = ReminderKind.Task,
            name = "Buy milk",
            pillsRemaining = 0.0,
            pillsPerDose = 1.0,
        )

        assertEquals(emptyList(), reconcile(medications = listOf(task)).raise)
    }

    @Test
    fun `reconcileRefills - raising - takes each raised Reminder's id from the minter, in order`() {
        val result = reconcile(medications = listOf(runningLow("Atorvastatin", "med-1")))

        assertEquals("minted-1", result.raise.firstOrNull()?.id)
    }
}

class ReconcileWithdrawingTest {
    /**
     * The acceptance criterion is that the supply can be updated after collecting
     * a prescription and the projection updates. A warning left standing after
     * that is the app telling someone to collect a prescription they are holding.
     */
    @Test
    fun `reconcileRefills - withdrawing - withdraws the warning once the supply has been topped up`() {
        val toppedUp = medication(pillsRemaining = 30.0, pillsPerDose = 1.0, refillReminderDays = 7)

        val result = reconcile(
            medications = listOf(toppedUp),
            standing = listOf(standingRefill("task-1", "Refill Atorvastatin")),
        )

        assertEquals(listOf("task-1"), result.retire.map { it.id })
    }

    /**
     * Clearing the pill count leaves the projection blind, not reassured. A
     * warning withdrawn on the strength of that is the app forgetting rather than
     * the person collecting.
     */
    @Test
    fun `reconcileRefills - withdrawing - keeps the warning when the supply stops being recorded at all`() {
        val untracked = medication(pillsPerDose = 1.0)

        val result = reconcile(
            medications = listOf(untracked),
            standing = listOf(standingRefill("task-1", "Refill Atorvastatin")),
        )

        assertEquals(emptyList(), result.retire)
    }

    @Test
    fun `reconcileRefills - withdrawing - keeps the warning when the dose size stops being recorded`() {
        val noDoseSize = medication(pillsRemaining = 3.0)

        val result = reconcile(
            medications = listOf(noDoseSize),
            standing = listOf(standingRefill("task-1", "Refill Atorvastatin")),
        )

        assertEquals(emptyList(), result.retire)
    }

    /**
     * Coming off a treatment is the one absence that IS reassuring: nothing is
     * being consumed, so nothing is running out, and a standing Refill Reminder
     * is an instruction to buy medicine somebody was told to stop taking.
     */
    @Test
    fun `reconcileRefills - withdrawing - withdraws the warning for a medication that has been stopped`() {
        val stopped = medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7, active = false)

        val result = reconcile(
            medications = listOf(stopped),
            standing = listOf(standingRefill("task-1", "Refill Atorvastatin")),
        )

        assertEquals(listOf("task-1"), result.retire.map { it.id })
        assertEquals(emptyList(), result.raise)
    }

    @Test
    fun `reconcileRefills - withdrawing - leaves alone a standing task that belongs to no medication here`() {
        val result = reconcile(
            medications = emptyList(),
            standing = listOf(standingRefill("task-1", "Refill Something Else")),
        )

        assertEquals(emptyList(), result.retire)
    }

    @Test
    fun `reconcileRefills - withdrawing - does not touch an ordinary errand that happens to be standing`() {
        val result = reconcile(
            medications = listOf(runningLow("Atorvastatin")),
            standing = listOf(standingRefill("task-1", "Buy milk")),
        )

        assertEquals(emptyList(), result.retire)
        assertEquals(1, result.raise.size)
    }

    @Test
    fun `reconcileRefills - withdrawing - has nothing to do for someone with no medications at all`() {
        assertEquals(RefillReconciliation(raise = emptyList(), retire = emptyList()), reconcile(medications = emptyList()))
    }
}
