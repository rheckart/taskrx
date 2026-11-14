package com.taskrx.mobile.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.WatchedSource
import com.taskrx.core.domain.normalizeSourceValue
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateWatchedSourceInput
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val sources: List<WatchedSource> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val adding: Boolean = false,
    val kind: SourceKind = SourceKind.Email,
    val value: String = "",
    val label: String = "",
) {
    /** The canonical spelling of the address or package, or null when it is not one. */
    val normalizedValue: String? get() = normalizeSourceValue(kind, value)
    val canSave: Boolean get() = normalizedValue != null && label.trim().isNotEmpty()
}

/**
 * Settings' watched sources: the senders and apps whose notices may be kept at all.
 *
 * A watched source alone moves nothing. It is the gate on storage; matching words live on a
 * reminder's "Notices about this", and only they can make a proposal. A share is never added
 * here: sharing something is its own approval.
 */
class SettingsViewModel(
    private val app: AppContainer,
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("settings", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = mutable

    fun refresh(): Job =
        viewModelScope.launch {
            try {
                val sources = app.watchSources.list(app.session.userId)
                mutable.update { it.copy(sources = sources, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                mutable.update { it.copy(loading = false) }
            }
        }

    fun startAdd() = mutable.update { it.copy(adding = true, kind = SourceKind.Email, value = "", label = "") }

    fun cancelAdd() = mutable.update { it.copy(adding = false) }

    fun setKind(kind: SourceKind) = mutable.update { it.copy(kind = kind, value = "") }

    fun setValue(value: String) = mutable.update { it.copy(value = value) }

    fun setLabel(label: String) = mutable.update { it.copy(label = label) }

    fun saveSource(): Job =
        viewModelScope.launch {
            val current = mutable.value
            val value = current.normalizedValue
            if (!current.canSave || value == null) return@launch
            try {
                app.watchSources.create(
                    CreateWatchedSourceInput(
                        userId = app.session.userId,
                        kind = current.kind,
                        value = value,
                        label = current.label.trim(),
                    ),
                )
                mutable.update { it.copy(adding = false, value = "", label = "") }
                toast(ToastRequest("Watching that now", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("adding a watched source failed", e)
                toast(ToastRequest("Couldn't add that", ToastKind.Error))
            }
            refresh()
        }

    fun remove(source: WatchedSource): Job =
        viewModelScope.launch {
            try {
                app.watchSources.remove(source.id)
                toast(ToastRequest("No longer watching that", ToastKind.Info))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("removing a watched source failed", e)
                toast(ToastRequest("Couldn't remove that", ToastKind.Error))
            }
            refresh()
        }
}
