package com.taskrx.core.refill

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind

/*
 * What recording a dose does to the supply.
 *
 * Until this existed nothing spent a pill: `pillsRemaining` moved only when someone typed a
 * new number, so "30 pills at one a day" read "30 days left" tomorrow as well as today and the
 * refill warning could not fire on its own (docs/CHANGELOG.md, "nothing consumes the supply").
 *
 * The rule, settled here so no screen has to decide it:
 *
 *  - Only a change INTO Completed spends. A Skipped dose was not taken, a Missed one was not,
 *    and a Snooze is a deferral. None of them touch the pills in the bottle.
 *  - It spends `pillsPerDose`, or one when none is set - the same default the server's
 *    `consumePills` uses.
 *  - The count stops at zero. A bottle that was already empty on paper is not driven negative.
 *  - A Reminder that tracks no supply (a task, or a medication with no count) spends nothing.
 *  - Completed -> Completed spends nothing, so a second tap or a re-sent answer cannot spend
 *    twice. Nothing moves an Instance OUT of Completed (a local Completed is never overwritten,
 *    see `shouldApplyRemoteReminderInstance`), so there is no refund to make.
 *  - A Completed that arrives from the server spends nothing here: the other device spent
 *    the pills, and the new count comes with the Reminder document.
 */

/** The doses a bottle is drawn down by when `pillsPerDose` is not set. */
const val DEFAULT_PILLS_PER_DOSE = 1.0

/**
 * The new `pillsRemaining` after an Instance moves [from] a status (null for a row created
 * already settled) [to] another, or null when the supply is not touched.
 */
fun supplyAfterDose(
    kind: ReminderKind,
    from: InstanceStatus?,
    to: InstanceStatus,
    pillsRemaining: Double?,
    pillsPerDose: Double?,
): Double? {
    if (kind != ReminderKind.Medication) return null
    if (to != InstanceStatus.Completed || from == InstanceStatus.Completed) return null
    if (pillsRemaining == null) return null
    val perDose = pillsPerDose?.takeIf { it > 0 } ?: DEFAULT_PILLS_PER_DOSE
    return maxOf(0.0, pillsRemaining - perDose)
}
