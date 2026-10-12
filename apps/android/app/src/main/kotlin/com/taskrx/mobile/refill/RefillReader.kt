package com.taskrx.mobile.refill

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.refill.ReconcileRefillsInput
import com.taskrx.core.refill.RefillProjection
import com.taskrx.core.refill.projectRefill
import com.taskrx.core.refill.reconcileRefills
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.IdGenerator
import com.taskrx.mobile.data.RandomUuid
import com.taskrx.mobile.data.RemindersRepository
import java.time.ZoneId

data class RefillRow(val medication: Reminder, val projection: RefillProjection)

data class RefillReading(
    /** Every active medication, soonest to run out first. */
    val rows: List<RefillRow>,
    /** Names of the Refill Reminders this pass added to the Backlog. */
    val raised: List<String>,
    /** Names of the Refill Reminders this pass withdrew as no longer needed. */
    val retired: List<String>,
)

/**
 * The Refill read: project every medication, then make the Backlog agree
 * (archive/expo-rn:apps/mobile/src/refill/read.ts).
 *
 * Every rule lives in :core - [projectRefill] says how long a supply lasts,
 * `planRefill` what a Refill Reminder is, [reconcileRefills] which ones the Backlog
 * gains and loses. This reads two tables, hands them over and writes down the answer.
 * The raise happens here and not on the screen so no second surface can forget it.
 *
 * Known gap, inherited: nothing decrements `pillsRemaining` when a dose is recorded, so
 * the projection only moves when somebody edits the count.
 *
 * Unlike Adherence, a failed write does not fail the read: the projection on screen is
 * correct with or without the Backlog entry, so the exception is the caller's to report.
 */
class RefillReader(
    private val reminders: RemindersRepository,
    private val mintId: IdGenerator = RandomUuid,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun read(userId: String, now: Long): RefillReading {
        // Stopped medications included, because reconcileRefills withdraws their standing
        // warnings; they are filtered out of `rows` below.
        val medications = reminders.list(userId, ReminderKind.Medication, includeInactive = true)
        // Active tasks only: a withdrawn Refill Reminder must not count as one standing,
        // or a medication running low a second time would never be warned about again.
        val standing = reminders.list(userId, ReminderKind.Task)

        val plan =
            reconcileRefills(
                ReconcileRefillsInput(medications, standing, now, mintId, zone()),
            )

        for (raised in plan.raise) {
            reminders.create(
                CreateReminderInput(
                    id = raised.id,
                    userId = raised.userId,
                    kind = raised.kind,
                    name = raised.name,
                    schedule = raised.schedule,
                    priority = raised.priority,
                ),
            )
        }
        for (retired in plan.retire) {
            reminders.softDelete(retired.id)
        }

        val rows =
            medications
                .filter { it.active }
                .map { RefillRow(it, projectRefill(it, now, zone())) }
                .sortedBy(::daysUntilEmpty)

        return RefillReading(rows, plan.raise.map { it.name }, plan.retire.map { it.name })
    }

    /**
     * Sort key: days until the supply runs out. A medication with no projection, or a
     * supply that outlasts the walk, sorts last - "nothing recorded" is not urgent and a
     * missing number is not a zero.
     */
    private fun daysUntilEmpty(row: RefillRow): Double =
        when (val projection = row.projection) {
            is RefillProjection.RefillOutlook -> projection.daysRemaining?.toDouble() ?: Double.POSITIVE_INFINITY
            else -> Double.POSITIVE_INFINITY
        }
}
