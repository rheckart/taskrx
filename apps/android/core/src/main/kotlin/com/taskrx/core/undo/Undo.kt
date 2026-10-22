package com.taskrx.core.undo

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.schedule.nextOccurrences
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import java.time.ZoneId

/*
 * Taking back an answer: which answers can be, and what happens after.
 *
 * Ported in spirit from Medisafe's "un-skip" and "un-take" (docs/medisafe-skip-snooze-findings.md).
 * An undo returns an Instance to `pending`, the state it was minted in, so the person can answer
 * the moment again. ADR 0008 records why a taking, which the sync layer otherwise guards as
 * final, may be undone, and why that is the only way it can be.
 *
 * Pure: no clock, no storage. The caller supplies `now`.
 */

/** The answers that can be taken back. `missed` is the app's guess and is answered, not undone. */
val UNDOABLE_STATUSES: Set<InstanceStatus> =
    setOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Snoozed)

/** Why an undo is not offered, or that it is. */
enum class UndoVerdict {
    Allowed,

    /** The Instance is not an answer: it is pending, or missed. */
    NotAnAnswer,

    /**
     * The moment is not one the Schedule predicts: an extra dose, a Backlog completion, or an
     * Instance left over from a Schedule that has since changed. Reopened, it would be a pending
     * row nobody was ever due to answer, and the sweep would record it as a miss that never was.
     */
    NotScheduled,

    /** Another Instance at the same moment is already open (issue #21), so this one would make two. */
    MomentOpenAgain,
}

/**
 * Whether an answer on [reminder] at [scheduledTime] can be taken back.
 *
 * [otherOpenAtMoment] is whether a different Instance for the same Reminder and moment is
 * unanswered. Undoing would leave two `pending` rows at one pairing, and the sweep would
 * mint a Missed verdict for each: one dose counted missed twice.
 */
fun undoVerdict(
    status: InstanceStatus,
    reminder: Reminder,
    scheduledTime: Long,
    otherOpenAtMoment: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): UndoVerdict =
    when {
        status !in UNDOABLE_STATUSES -> UndoVerdict.NotAnAnswer
        !isScheduledOccurrence(reminder, scheduledTime, zone) -> UndoVerdict.NotScheduled
        otherOpenAtMoment -> UndoVerdict.MomentOpenAgain
        else -> UndoVerdict.Allowed
    }

/**
 * Whether [scheduledTime] is exactly one of [reminder]'s Occurrences. `nextOccurrences` counts
 * from an exclusive instant, hence the millisecond before.
 */
fun isScheduledOccurrence(reminder: Reminder, scheduledTime: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
    nextOccurrences(reminder, scheduledTime - 1, 1, zone).firstOrNull()?.fireAt == scheduledTime

/**
 * Whether the next sweep will record the moment as Missed straight away once it is open again:
 * its Grace Period (ADR 0005) is already over. The undo is honest about this rather than hiding
 * it; the person can still answer a Missed moment.
 */
fun undoWillBeMissed(scheduledTime: Long, now: Long, gracePeriodMs: Long = GRACE_PERIOD_MS): Boolean =
    scheduledTime < now - gracePeriodMs
