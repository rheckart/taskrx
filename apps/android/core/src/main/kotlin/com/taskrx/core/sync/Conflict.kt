package com.taskrx.core.sync

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.StatusTracked
import com.taskrx.core.domain.Timestamped

/*
 * Pure sync-conflict decisions, used when applying a remote pull to the local
 * SQLite store.
 *
 * Two rules, and only the second is interesting: last-write-wins with the LOCAL
 * row winning ties, and — for an Instance — a locally `completed` row is only ever
 * replaced by the person's own undo (ADR 0008).
 *
 * A medication/dose-log pair used to sit alongside these, written first and
 * copied here deliberately rather than generalized, on the reasoning that the
 * legacy pair was scheduled for deletion and ADR 0001 would rename `taken` to
 * `completed` on the unified side alone. Both have now happened, leaving these
 * as the only copy — which is what that reasoning predicted, and why there was
 * never an abstraction to unpick.
 */

/** What the Reminder rule reads: a `Reminder` is one, and so is a bare timestamp row. */
typealias ReminderWithTimestamps = Timestamped

/** What the Instance rule reads: the timestamp and the status. */
typealias ReminderInstanceWithConflictFields = StatusTracked

/**
 * Decide whether an incoming remote reminder should overwrite the local row.
 * Last-write-wins, with the local row winning ties.
 */
fun shouldApplyRemoteReminder(
    local: ReminderWithTimestamps?,
    remote: ReminderWithTimestamps,
): Boolean {
    if (local == null) {
        return true
    }
    return remote.updatedAt > local.updatedAt
}

/**
 * Decide whether an incoming remote instance should overwrite the local row.
 *
 * A locally `completed` instance is overwritten by a remote pull only by a newer
 * `pending` — the safety-critical rule, and the whole reason this is a separate
 * function from [shouldApplyRemoteReminder]. A taking the person recorded on this
 * device must not be erased by a stale copy arriving from another one, and a stale
 * copy is a skip, a snooze, a miss or an older completion. The one thing that may
 * erase a taking is the person undoing it, which reaches the server as `reopen` and
 * comes back as `pending` with a newer `updatedAt` (ADR 0008). Everything else is
 * last-write-wins with the local row winning ties.
 *
 * Above all of that: a row with a change this device has not pushed yet is never
 * overwritten ([localHasUnpushedChange]). Without it, a take that had synced and was then
 * undone offline would be put straight back to `completed` by the next pull, which still
 * carries the take: the undo would be lost on this device, and the queued `reopen` would
 * later leave it disagreeing with the server for good. The change is the newest thing the
 * person did; the pull has nothing newer to say about it until it has been pushed.
 *
 * `snoozed` gets no such protection: a deferral is not an answer, so a later
 * remote answer legitimately replaces it.
 *
 * This guard is the one place in the sync layer where the status is
 * load-bearing rather than merely carried, which is why ADR 0001's rename of
 * `taken` to `completed` had to move this line with it.
 */
fun shouldApplyRemoteReminderInstance(
    local: ReminderInstanceWithConflictFields?,
    remote: ReminderInstanceWithConflictFields,
    localHasUnpushedChange: Boolean = false,
): Boolean {
    if (local == null) {
        return true
    }
    if (localHasUnpushedChange) {
        return false
    }
    if (local.status == InstanceStatus.Completed) {
        return remote.status == InstanceStatus.Pending && remote.updatedAt > local.updatedAt
    }
    // The server ignores every write to a `completed` row but `reopen` (`isCompleted` in
    // reminderInstances.ts), so a remote completion is final whatever its timestamp.
    // Last-write-wins here would leave this device on a `missed` or `snoozed` it stamped
    // later than a taking recorded elsewhere; pushing that status is a server no-op, so the
    // two would never converge and this device would score the dose as a miss.
    if (remote.status == InstanceStatus.Completed) {
        return true
    }
    return remote.updatedAt > local.updatedAt
}
