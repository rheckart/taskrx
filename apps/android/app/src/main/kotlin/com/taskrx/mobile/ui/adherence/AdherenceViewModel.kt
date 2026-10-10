package com.taskrx.mobile.ui.adherence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.adherence.AdherenceReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AdherenceUiState(
    val reading: AdherenceReading? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * "Am I actually taking my medication?" Mirrors apps/mobile/app/(app)/adherence.tsx.
 *
 * Every rule is elsewhere: `computeAdherence` decides the figure, `adherencePeriod` the
 * window, and [com.taskrx.mobile.adherence.AdherenceReader] that the missed sweep runs
 * before either is consulted. This asks for a reading and shows it.
 *
 * A failed read is SHOWN, unlike the timeline's failed sweep. There the day is right with
 * or without the sweep; here a figure computed over a sweep that did not finish is the
 * flattering one, and a wrong number that looks like an answer is worse than no number.
 */
class AdherenceViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(AdherenceUiState())
    val state: StateFlow<AdherenceUiState> = mutable

    private val loads = Mutex()

    /** Called on every resume: answering a dose leaves this screen and comes back to a stale figure otherwise. */
    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        try {
            val reading = app.adherence.read(app.session.userId, app.clock())
            mutable.update { it.copy(reading = reading, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The old figure goes with it: it was computed before whatever just failed.
            mutable.update { it.copy(reading = null, error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }
}
