package com.taskrx.mobile.ui.supplies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.Change
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.refill.RefillRow
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SuppliesState(
    val rows: List<RefillRow> = emptyList(),
    /** Names of the Refill Reminders the last read added to the Backlog. */
    val raised: List<String> = emptyList(),
    /** Names of the Refill Reminders the last read withdrew. */
    val retired: List<String> = emptyList(),
    /** The medication whose supply is being edited, if any. */
    val editingId: String? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/** The three numbers a supply is projected from. Null is "not recorded", which is not zero. */
data class SupplyDraft(
    val pillsRemaining: Double?,
    val pillsPerDose: Double?,
    val refillReminderDays: Int?,
)

/**
 * "Am I about to run out?" Mirrors archive/expo-rn:apps/mobile/app/(app)/refill.tsx.
 *
 * Every rule is :core's: `projectRefill` says how long a supply lasts, `planRefill` what a
 * Refill Reminder is, `reconcileRefills` which ones the Backlog gains and loses.
 * [com.taskrx.mobile.refill.RefillReader] runs that pass BEFORE this has anything to show,
 * so opening the screen is what raises and withdraws them, and no second surface can forget.
 * What the pass did is said out loud: a Reminder that appears or vanishes with no account
 * of why is the app acting behind somebody's back.
 */
class SuppliesViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(SuppliesState())
    val state: StateFlow<SuppliesState> = mutable

    private val loads = Mutex()

    /** Called on every resume: the count it comes back to would otherwise be the one from before the pharmacy. */
    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            val reading = app.refills.read(userId, app.clock())
            // No reschedule after the raise or withdrawal: a Refill Reminder has no Schedule, so
            // it never produces an Occurrence and arming cannot change.
            mutable.update { it.copy(rows = reading.rows, raised = reading.raised, retired = reading.retired, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }

    fun edit(medicationId: String) = mutable.update { it.copy(editingId = medicationId) }

    fun cancelEdit() = mutable.update { it.copy(editingId = null) }

    /**
     * Every field is written, so clearing a box clears the column: "I no longer track this"
     * has to be sayable. The read that follows re-projects, and may raise or withdraw.
     */
    fun saveSupply(medicationId: String, draft: SupplyDraft): Job =
        viewModelScope.launch {
            mutable.update { it.copy(editingId = null) }
            try {
                app.reminders.update(
                    medicationId,
                    UpdateReminderPatch(
                        pillsRemaining = Change.To(draft.pillsRemaining),
                        pillsPerDose = Change.To(draft.pillsPerDose),
                        refillReminderDays = Change.To(draft.refillReminderDays),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A toast and not the error screen: the projection on screen is still the truth about what is stored.
                app.toaster.show(ToastRequest(e.message ?: "Couldn't save the supply", ToastKind.Error))
                return@launch
            }
            loads.withLock { load() }
        }
}
