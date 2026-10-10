package com.taskrx.mobile.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.InstanceFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One recorded Instance, titled from its parent Reminder. */
data class HistoryItem(val instance: ReminderInstance, val name: String, val dosage: String?)

data class HistoryState(
    /** Newest scheduled time first, as the repository returns them. */
    val items: List<HistoryItem> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

const val UNKNOWN_REMINDER: String = "Unknown reminder"

/**
 * The history of what became of each Occurrence. Mirrors apps/mobile/app/(app)/dose/history.tsx.
 *
 * Both Kinds are listed, each titled from its own Reminder. (The Expo screen read only
 * medications for the titles, so a task's row said "Unknown medication"; the Instances were
 * always Kind-neutral and so is this.) A Reminder that has since been stopped still names
 * its history, which is why inactive Reminders are read too. A parent this device has never
 * seen is named plainly rather than hidden: the row is still a true record.
 */
class HistoryViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryState())
    val state: StateFlow<HistoryState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            val instances = app.instances.list(InstanceFilter(userId))
            val parents = app.reminders.list(userId, includeInactive = true).associateBy { it.id }
            val items =
                instances.map { instance ->
                    val parent = parents[instance.reminderId]
                    HistoryItem(instance, parent?.name ?: UNKNOWN_REMINDER, parent?.dosage)
                }
            mutable.update { it.copy(items = items, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }
}
