package com.taskrx.core.refill

import com.taskrx.core.domain.Reminder
import java.time.ZoneId

/*
 * Reconciling the Backlog with the refill projections: which Refill Reminders
 * to raise, and which standing ones to withdraw.
 *
 * This is a rule, so it is here rather than in the device read that performs the
 * writes. `docs/specs/fused-timeline.md` is explicit about the boundary - the
 * on-device layer "persists what the pure functions decide", and "if a rule ends
 * up inside a repository, that is a signal the extraction was incomplete".
 *
 * ## What a "standing" Refill Reminder is
 *
 * There is no column pointing from a raised Reminder back at the medication it
 * came from, so the pairing is by name - [refillReminderName], one definition
 * shared by the raiser and this check. That makes it exact for the Reminders
 * this code raises and blind to a same-named task somebody typed themselves,
 * which is worth naming and much cheaper than a schema change for a collision
 * that requires typing "Refill Atorvastatin" by hand.
 *
 * ## Why withdrawing needs more than "no refill due"
 *
 * A supply the app can no longer see is not a supply that has been replenished.
 * Clearing the pill count leaves the projection blind rather than reassured, and
 * a warning retired on the strength of that is the app forgetting rather than
 * the person collecting. So an unprojectable medication leaves a standing
 * warning alone - with one exception, which is the medication having been
 * stopped: nothing is being consumed, so there is nothing left to warn about,
 * and a Refill Reminder for a treatment somebody has come off is an instruction
 * to buy medicine they were told to stop taking.
 *
 * Pure: the caller supplies the instant, the ids, and does the writing.
 */

data class ReconcileRefillsInput(
    /**
     * Every medication to consider, stopped ones included. A stopped medication
     * raises nothing, but it can still have a standing warning to withdraw.
     */
    val medications: List<Reminder>,
    /** The task Reminders currently standing, from which the raised ones are recognised. */
    val standing: List<Reminder>,
    /** The instant the supplies are judged at, Unix ms. */
    val now: Long,
    /**
     * Ids for the Reminders to be raised, one per call.
     *
     * A generator rather than a list, because how many will be raised is what this
     * function is working out. It keeps the id-minting outside, the same way
     * [PlanRefillOptions] does.
     */
    val mintId: () -> String,
    /** The zone local calendar days are counted in; the device's unless a test pins one. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

data class RefillReconciliation(
    /** Refill Reminders to write, exactly as [planRefill] built them. */
    val raise: List<Reminder>,
    /** Standing Refill Reminders to withdraw. */
    val retire: List<Reminder>,
)

/**
 * What the Backlog should gain and lose for it to agree with the projections.
 *
 * Idempotent by construction: a medication whose warning is already standing
 * appears in neither list, so running this on every glance at a screen does
 * nothing after the first.
 */
fun reconcileRefills(input: ReconcileRefillsInput): RefillReconciliation {
    val standingByName = LinkedHashMap<String, Reminder>()
    // Later entries win, as a JS `Map` built from pairs does.
    for (task in input.standing) standingByName[task.name] = task
    val raise = mutableListOf<Reminder>()
    val retire = mutableListOf<Reminder>()

    for (medication in input.medications) {
        val name = refillReminderName(medication)
        val alreadyStanding = standingByName[name]
        val decision = planRefill(
            medication,
            PlanRefillOptions(refillReminderId = input.mintId(), now = input.now, zone = input.zone),
        )

        if (decision is RefillDecision.Needed) {
            if (alreadyStanding != null) continue
            raise.add(decision.refillReminder)
            // Recorded as standing straight away, so that two medications sharing a
            // name - a duplicate somebody entered twice, most likely - produce one
            // Refill Reminder rather than two that nothing afterwards can tell apart.
            standingByName[name] = decision.refillReminder
            continue
        }

        if (alreadyStanding == null) continue

        val projection = projectRefill(medication, input.now, input.zone)
        val withdrawable =
            projection !is RefillProjection.Unprojectable || projection.reason == UnprojectableReason.Stopped
        if (withdrawable) {
            retire.add(alreadyStanding)
            standingByName.remove(name)
        }
    }

    return RefillReconciliation(raise = raise, retire = retire)
}
