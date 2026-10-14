package com.taskrx.mobile.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.sync.StuckChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SyncProblemsState(
    val items: List<StuckChange> = emptyList(),
    val loading: Boolean = true,
    val retrying: Boolean = false,
    val error: String? = null,
)

/** The changes the sync gave up on, and the one action on them: send them again. */
class SyncProblemsViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(SyncProblemsState())
    val state: StateFlow<SyncProblemsState> = mutable

    fun refresh(): Job =
        viewModelScope.launch {
            try {
                mutable.update { it.copy(items = app.stuckChanges.list(), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                mutable.update { it.copy(loading = false) }
            }
        }

    /** Puts every stuck change back to waiting; the sync coordinator sees the queue grow and sends them. */
    fun retry(): Job =
        viewModelScope.launch {
            mutable.update { it.copy(retrying = true) }
            try {
                app.stuckChanges.retryAll()
                mutable.update { it.copy(items = app.stuckChanges.list(), error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                mutable.update { it.copy(retrying = false) }
            }
        }
}
