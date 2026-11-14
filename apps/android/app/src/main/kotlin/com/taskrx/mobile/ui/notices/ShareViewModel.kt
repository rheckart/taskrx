package com.taskrx.mobile.ui.notices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateNoticeInput
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareState(
    val text: String = "",
    val title: String = "",
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = text.isNotBlank() && title.isNotBlank() && !saving && !saved
}

/**
 * The share intake: the person shared something with TaskRx, so the share itself is the
 * approval. Nothing is read from the text; it is kept as a Notice and the Notices screen is
 * where a date is set and a task made.
 */
class ShareViewModel(
    private val app: AppContainer,
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("share", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
) : ViewModel() {
    private val mutable = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = mutable

    /** Seeds the form from the shared text, once. */
    fun load(text: String?) {
        if (text.isNullOrBlank() || mutable.value.text.isNotEmpty()) return
        val firstLine = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        mutable.value = ShareState(text = text, title = firstLine.take(120))
    }

    fun setTitle(title: String) = mutable.update { it.copy(title = title) }

    fun save(): Job =
        viewModelScope.launch {
            val current = mutable.value
            if (!current.canSave) return@launch
            mutable.update { it.copy(saving = true, error = null) }
            try {
                app.notices.create(
                    CreateNoticeInput(
                        userId = app.session.userId,
                        sourceKind = SourceKind.Share,
                        sourceValue = "share",
                        title = current.title.trim(),
                        sentence = current.text.trim(),
                        receivedAt = app.clock(),
                    ),
                )
                toast(ToastRequest("Saved to Notices", ToastKind.Success))
                mutable.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("saving a shared notice failed", e)
                mutable.update { it.copy(saving = false, error = e.message ?: "Save failed") }
                toast(ToastRequest("Couldn't save that", ToastKind.Error))
            }
        }
}
