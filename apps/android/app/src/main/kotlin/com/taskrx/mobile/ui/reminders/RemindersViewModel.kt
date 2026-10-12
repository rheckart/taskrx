package com.taskrx.mobile.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.mobile.AppContainer
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

data class ReminderEntry(val reminder: Reminder, val lastCompleted: ReminderInstance?)

data class RemindersState(
    val entries: List<ReminderEntry> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Every live Reminder, of either Kind. Mirrors archive/expo-rn:apps/mobile `meds/index.tsx`, widened: that
 * list was the medications only, and it had no way to stop one. A Reminder is one aggregate
 * with a Kind axis, so this lists them together and the Kind is a word on the row.
 *
 * Stopping is the repository's soft delete (`active = false`), never a row delete: the
 * Instances cascade from the Reminder, and a stopped medication's recorded history still
 * has to be there for Adherence and for the history list. The armed notifications are
 * brought in line straight after, so a stopped Reminder cannot ring once more.
 */
class RemindersViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(RemindersState())
    val state: StateFlow<RemindersState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            val entries =
                app.reminders.list(userId).map { ReminderEntry(it, app.instances.lastCompletedFor(userId, it.id)) }
            mutable.update { it.copy(entries = entries, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }

    fun stop(reminder: Reminder): Job =
        viewModelScope.launch {
            try {
                app.reminders.softDelete(reminder.id)
                app.notifications.rescheduleAfterWrite(app.session.userId)
                app.toaster.show(ToastRequest("Stopped ${reminder.name}", ToastKind.Success))
                loads.withLock { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest(e.message ?: "Couldn't stop it", ToastKind.Error))
            }
        }
}
