package com.taskrx.mobile.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val DRUG_SEARCH_DEBOUNCE_MS: Long = 300

sealed interface SearchStatus {
    /** Nothing typed yet. */
    data object Idle : SearchStatus

    data object Searching : SearchStatus

    /** The lookup cannot be made from here. Not a failure of the query: there is nothing to retry with the same words. */
    data object Unavailable : SearchStatus

    data class Failed(val message: String) : SearchStatus

    data class Done(val results: List<DrugMatch>) : SearchStatus
}

data class DrugSearchState(val query: String = "", val status: SearchStatus = SearchStatus.Idle)

/**
 * Drug search. Mirrors archive/expo-rn:apps/mobile `DrugSearch.tsx`.
 *
 * Debounced, and guarded against a stale response: only the latest query may write its
 * result. Cancelling the previous job is that guard, so a slow answer for "met" cannot
 * overwrite the results for "metformin", the race that is invisible until someone types
 * faster than the network.
 */
class DrugSearchViewModel(
    private val service: DrugSearchService,
    initialQuery: String = "",
    private val debounceMs: Long = DRUG_SEARCH_DEBOUNCE_MS,
) : ViewModel() {
    private val mutable = MutableStateFlow(DrugSearchState(query = initialQuery))
    val state: StateFlow<DrugSearchState> = mutable

    private var pending: Job? = null

    init {
        if (initialQuery.isNotBlank()) setQuery(initialQuery)
    }

    fun setQuery(query: String): Job {
        pending?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            mutable.value = DrugSearchState(query = query)
            return Job().also { it.complete() }
        }
        mutable.value = DrugSearchState(query = query, status = SearchStatus.Searching)
        return viewModelScope
            .launch {
                delay(debounceMs)
                val outcome =
                    try {
                        SearchStatus.Done(service.search(trimmed))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: DrugSearchUnavailable) {
                        SearchStatus.Unavailable
                    } catch (e: Exception) {
                        SearchStatus.Failed(e.message ?: "Search failed")
                    }
                // A service that ignores cancellation can still return after a newer query
                // started; this is what stops its answer being written.
                ensureActive()
                mutable.update { it.copy(status = outcome) }
            }.also { pending = it }
    }
}
