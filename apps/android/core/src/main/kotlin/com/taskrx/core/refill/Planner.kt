package com.taskrx.core.refill

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.noneTimes
import java.time.ZoneId

/*
 * The Refill Reminder: what to raise, and when.
 *
 * Ported from `RefillPlanner.Plan` in `TaskRx.Core/RefillPlanner.cs` in the
 * retired .NET app (paired with `RefillDecision.cs`). Three
 * differences from the C# source, all deliberate.
 *
 * - **Result shape.** The C# returns a `RefillDecision(bool NeedsRefill,
 *   Reminder? RefillReminder)` record, where `RefillReminder` is `null`
 *   whenever `NeedsRefill` is `false`. Here that pairing is a sealed type, so a
 *   caller that has matched on it never has to null-check the Reminder - the
 *   [RefillDecision.NoRefill] variant has no such property at all.
 * - **Reminder construction is a caller input, not a side effect.** The C#
 *   builds the refill `Reminder` with `Id = Guid.Empty` and leaves
 *   `CreatedAt`/`UpdatedAt` at their C# default. Being pure, this must not mint
 *   a UUID or read the clock itself - so the id and timestamp for a newly
 *   planned refill reminder are supplied by the caller via [PlanRefillOptions].
 * - **The threshold is gone**, and with it the `dosesInLookaheadWindow`
 *   parameter. `RefillPlanner.cs:27` raised a refill when `pillsRemaining <=
 *   dosesInLookaheadWindow * 3` - a comparison of pills against doses, which
 *   ignores `pillsPerDose` entirely and never has a date to show. Issue #17
 *   rules both out. [projectRefill] now decides, from the supply, the Schedule
 *   and the warning window the Reminder itself carries; this function asks it
 *   and builds the Reminder. Keeping the old threshold beside the new
 *   projection would have left two rules that disagree, one of them on screen.
 */

data class PlanRefillOptions(
    /** Id for the generated refill `Reminder`; C#'s `Guid.Empty` (RefillPlanner.cs:37). */
    val refillReminderId: String,
    /**
     * The instant the plan is made: both the `createdAt`/`updatedAt` of the
     * generated refill `Reminder` and the point the supply is projected forward
     * from, in Unix ms. One value, so the Reminder cannot be stamped at a moment
     * other than the one it was judged at.
     */
    val now: Long,
    /** The zone local calendar days are counted in; the device's unless a test pins one. */
    val zone: ZoneId = ZoneId.systemDefault(),
)

sealed interface RefillDecision {
    data object NoRefill : RefillDecision

    data class Needed(val refillReminder: Reminder) : RefillDecision
}

/**
 * What a Refill Reminder for [medication] is called.
 *
 * Exposed because it is also the only handle a caller has on one it raised
 * earlier: the raise has to be idempotent - a screen that can be opened twice
 * must not leave two - and this name is what identifies the Reminder already
 * standing. Stated here so the raiser and the check cannot drift apart.
 */
fun refillReminderName(medication: Reminder): String = "Refill ${medication.name}"

/**
 * The Refill Reminder to raise for [medication], if one is due.
 *
 * A `task`, not a `medication`: collecting a prescription is an errand, and
 * ADR 0003 keeps tasks out of the medication notification Horizon so that
 * raising one can never cost a dose its alarm.
 */
fun planRefill(medication: Reminder, options: PlanRefillOptions): RefillDecision {
    val projection = projectRefill(medication, options.now, options.zone)
    if (projection !is RefillProjection.RefillOutlook || !projection.needsRefill) {
        return RefillDecision.NoRefill
    }

    val refillReminder = createReminder(
        id = options.refillReminderId,
        userId = medication.userId,
        kind = ReminderKind.Task,
        name = refillReminderName(medication),
        createdAt = options.now,
        updatedAt = options.now,
        priority = Priority.High,
        active = true,
        // `noneTimes()`, where the C# had a daily schedule with no times. That
        // variant did not exist when this was ported, and an empty daily schedule
        // is precisely the misconfiguration `none` was added to replace: it
        // produces no Occurrences while claiming to be a daily schedule, so the
        // raised Reminder would reach neither the timeline nor the Backlog and
        // nobody would ever see it. `none` is what the Backlog is keyed on, and the
        // Backlog is where an errand with no fixed moment belongs.
        schedule = noneTimes(),
        tags = emptyList(),
        caregiverIds = emptyList(),
    )

    return RefillDecision.Needed(refillReminder)
}
