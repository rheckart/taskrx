package com.taskrx.mobile.format

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.undo.UndoVerdict
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import com.taskrx.mobile.undo.UndoResult
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.ReminderKind

@RunWith(RobolectricTestRunner::class)
class UndoWordsTest {
    private val instance = createReminderInstance("i", "r", "u", ReminderKind.Medication, 0, 0, 0)

    @Test
    fun `an undo inside the grace period just says the answer is taken back`() {
        assertEquals(ToastRequest("Answer taken back", ToastKind.Info), undoToast(UndoResult.Reopened(instance, willBeMissed = false)))
    }

    @Test
    fun `an undo past the grace period says it will count as missed`() {
        assertEquals(
            ToastRequest("Answer taken back. This one is past its grace period and will count as missed.", ToastKind.Info),
            undoToast(UndoResult.Reopened(instance, willBeMissed = true)),
        )
    }

    @Test
    fun `a refusal is an error naming why`() {
        assertEquals(
            ToastRequest("This moment was opened again. Answer that one instead.", ToastKind.Error),
            undoToast(UndoResult.Refused(UndoVerdict.MomentOpenAgain)),
        )
    }

    @Test
    fun `every verdict has a sentence`() {
        assertEquals("Answer taken back", undoRefusal(UndoVerdict.Allowed))
        assertEquals("There is nothing to undo here", undoRefusal(UndoVerdict.NotAnAnswer))
        assertEquals("This one wasn't scheduled, so it can't be taken back", undoRefusal(UndoVerdict.NotScheduled))
        assertEquals("This moment was opened again. Answer that one instead.", undoRefusal(UndoVerdict.MomentOpenAgain))
    }

    @Test
    fun `an Instance that is gone is an error`() {
        assertEquals(ToastRequest("Couldn't find that reminder", ToastKind.Error), undoToast(UndoResult.NotFound))
    }
}
