package com.taskrx.core.domain

/*
 * The explicit lifecycle of a Reminder Instance.
 *
 * Before this file the rules about what a status could become were spread across the
 * answer screen, the repositories, the push call shapes, the conflict rules, the undo
 * rules and the supply rules. Each agreed by coincidence rather than by construction,
 * and a new surface could only learn the rules by reading all of them. This is the one
 * place they are written down.
 *
 * Two questions are deliberately kept apart:
 *
 *  - [availableActions] is the person's question: what may this screen offer? A `skipped`
 *    row is an answer, so it offers Undo and not Complete, even though a later write may
 *    legitimately rewrite it (an import, a repair, a test).
 *  - [allowsInstanceTransition] is the store's question: may this row's status be rewritten
 *    from `from` to `to` at all? It is broader, because internal writers (a batch, a sync repair) must
 *    not be told that a legal row is impossible merely because the UI would not offer it.
 *
 * The statuses themselves stay in `Enums.kt`. `ANSWERABLE_STATUSES` and
 * `UNDOABLE_STATUSES` are derived from this file, so the answer screen, the timeline
 * lookup, the repository and the undo gate cannot drift apart.
 */

/** What a person may do to an Instance. The sweep's Missed verdict is not an action. */
enum class InstanceAction(val wire: String) {
    /** Record it as done. The single word for both Kinds (ADR 0001). */
    Complete("complete"),

    /** Record that the person deliberately chose not to do it. */
    Skip("skip"),

    /** Defer it to a later moment. A deferral, not an answer. */
    Snooze("snooze"),

    /** Take an answer back, returning the row to [InstanceStatus.Pending] (ADR 0008). */
    Undo("undo"),

    /** Correct when a completed dose was taken. Only a completed row has such a time. */
    ChangeCompletedTime("change-completed-time"),
}

/**
 * The statuses a person can still answer: Pending, Snoozed and Missed.
 *
 * A snooze is a deferral, not an answer, and a Missed moment is the app's guess, so both
 * stay open. Completed and Skipped are answers and are closed until undone.
 */
internal val instanceAnswerableStatuses: Set<InstanceStatus> =
    setOf(InstanceStatus.Pending, InstanceStatus.Snoozed, InstanceStatus.Missed)

/**
 * The answers that can be taken back: a completion, a skip or a snooze.
 *
 * Missed is not one: it is the app's guess, answered rather than undone. Pending never
 * needed an undo.
 */
internal val instanceUndoableStatuses: Set<InstanceStatus> =
    setOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Snoozed)

/**
 * What [status] offers the person, in one place.
 *
 * A Missed row offers the three answers and no Undo, because it is answered rather than
 * undone. A Snoozed row offers all four: it is both open (a deferral is not an answer)
 * and undoable (the deferral itself can be taken back). A Completed row offers Undo and
 * the correction of its taken time.
 */
fun availableActions(status: InstanceStatus): Set<InstanceAction> =
    buildSet {
        if (status in instanceAnswerableStatuses) {
            add(InstanceAction.Complete)
            add(InstanceAction.Skip)
            add(InstanceAction.Snooze)
        }
        if (status in instanceUndoableStatuses) add(InstanceAction.Undo)
        if (status == InstanceStatus.Completed) add(InstanceAction.ChangeCompletedTime)
    }

/**
 * The status [action] leaves [from] in, or null when the action is not offered.
 *
 * Callers that already render from [availableActions] can use this to derive the write
 * they are about to make from the same table, rather than repeating the mapping.
 */
fun actionTarget(action: InstanceAction, from: InstanceStatus): InstanceStatus? {
    if (action !in availableActions(from)) return null
    return when (action) {
        InstanceAction.Complete -> InstanceStatus.Completed
        InstanceAction.Skip -> InstanceStatus.Skipped
        InstanceAction.Snooze -> InstanceStatus.Snoozed
        InstanceAction.Undo -> InstanceStatus.Pending
        InstanceAction.ChangeCompletedTime -> InstanceStatus.Completed
    }
}

/**
 * Whether an Instance may be rewritten from [from] to [to]. A null [from] is a row being
 * created, where the minting rule (a pending occurrence, the sweep's Missed, or a
 * Backlog completion) has already decided the status in `:core`.
 *
 * A row may always be rewritten to the status it already has. That keeps every answer
 * operation idempotent: a re-sent write, a second tap that lost the race, or a corrected
 * taken time all land on the same row instead of opening a second one.
 *
 * The edges that are not self-transitions:
 *
 *  - Pending may become any status: it is the row before an answer or the sweep.
 *  - Snoozed may become any status: a deferral can be answered, missed, or deferred again.
 *  - Skipped may be reopened, answered in place, or snoozed. It may not become Missed:
 *    the sweep never touches an answer.
 *  - Missed may be answered or snoozed (a late dose settled or put off), but it is not
 *    reopened: undo refuses it, because Missed is not the person's answer.
 *  - Completed is final until the person undoes it (ADR 0008). The sync layer treats
 *    the same edge as safety-critical, so the two cannot disagree.
 */
fun allowsInstanceTransition(from: InstanceStatus?, to: InstanceStatus): Boolean {
    if (from == null || from == to) return true
    return when (from) {
        InstanceStatus.Pending -> true
        InstanceStatus.Snoozed -> true
        InstanceStatus.Skipped -> to != InstanceStatus.Missed
        InstanceStatus.Missed -> to != InstanceStatus.Pending
        InstanceStatus.Completed -> to == InstanceStatus.Pending
    }
}

/**
 * Why [from] cannot become [to], or null when it can. The repository puts this in its
 * refusal so a failed write names the rule instead of an enum pair. [from] is non-null
 * because a row being created may take any status: only an existing row can be refused.
 */
fun instanceTransitionProblem(from: InstanceStatus, to: InstanceStatus): String? =
    if (allowsInstanceTransition(from, to)) {
        null
    } else {
        "Cannot move a Reminder Instance from ${from.wire} to ${to.wire}; see InstanceLifecycle."
    }