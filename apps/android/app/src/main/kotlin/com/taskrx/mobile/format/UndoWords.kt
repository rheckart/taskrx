package com.taskrx.mobile.format

import com.taskrx.core.undo.UndoVerdict
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import com.taskrx.mobile.undo.UndoResult

/** Said when an answer has been taken back and the moment is already past its Grace Period. */
const val UNDO_PAST_GRACE: String = "This one is past its grace period and will count as missed."

/** What an undo says for itself, as a toast. One place, so the card and the timeline row agree. */
fun undoToast(result: UndoResult): ToastRequest =
    when (result) {
        is UndoResult.Reopened ->
            if (result.willBeMissed) {
                ToastRequest("Answer taken back. $UNDO_PAST_GRACE", ToastKind.Info)
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
