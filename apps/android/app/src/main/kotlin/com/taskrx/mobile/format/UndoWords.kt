package com.taskrx.mobile.format

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.undo.UndoVerdict
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import com.taskrx.mobile.undo.UndoResult

/** Said when a dose's answer has been taken back and the moment is already past its Grace Period. */
const val UNDO_PAST_GRACE: String = "This one is past its grace period and will count as missed."

/** The same for a task: the stored status is still `missed`, but a task is shown as Overdue. */
const val UNDO_PAST_GRACE_TASK: String = "This one is past its grace period and will count as overdue."

/** The word the app uses for a moment reopened past its Grace Period, per Kind. */
fun undoPastGrace(kind: ReminderKind): String = if (kind == ReminderKind.Task) UNDO_PAST_GRACE_TASK else UNDO_PAST_GRACE

/** What an undo says for itself, as a toast. One place, so the card and the timeline row agree. */
fun undoToast(result: UndoResult): ToastRequest =
    when (result) {
        is UndoResult.Reopened ->
            if (result.willBeMissed) {
                ToastRequest("Answer taken back. ${undoPastGrace(result.instance.reminderKind)}", ToastKind.Info)
            } else {
                ToastRequest("Answer taken back", ToastKind.Info)
            }
        is UndoResult.Refused -> ToastRequest(undoRefusal(result.verdict), ToastKind.Error)
        UndoResult.NotFound -> ToastRequest("Couldn't find that reminder", ToastKind.Error)
    }

/** Why an undo was refused, in a sentence. */
fun undoRefusal(verdict: UndoVerdict): String =
    when (verdict) {
        UndoVerdict.Allowed -> "Answer taken back"
        UndoVerdict.NotAnAnswer -> "There is nothing to undo here"
        UndoVerdict.NotScheduled -> "This one wasn't scheduled, so it can't be taken back"
        UndoVerdict.MomentOpenAgain -> "This moment was opened again. Answer that one instead."
    }
