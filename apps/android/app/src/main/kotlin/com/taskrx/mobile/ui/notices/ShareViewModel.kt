package com.taskrx.mobile.ui.notices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.notices.readSharedMoment
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateNoticeInput
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShareState(
    val text: String = "",
    val title: String = "",
    /** Suggested moment read from [text] (or edited by the person) for Create new task; null if none was read. */
    val detectedAt: Long? = null,
    /** The words in [text] that [detectedAt] came from, shown as a hint. */
    val matchedText: String? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = text.isNotBlank() && title.isNotBlank() && !saving && !saved
}

/**
 * The share intake: the person shared something with TaskRx, so the share itself is the
 * approval. A clear date/time in the text is suggested for pre-filling Create new task, and
 * nothing is saved until the person taps Create new task or Save to Notices.
 */
class ShareViewModel(
    private val app: AppContainer,
    initialText: String = "",
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("share", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
) : ViewModel() {
    private val mutable = MutableStateFlow(seed(initialText))
    val state: StateFlow<ShareState> = mutable

    private fun seed(text: String): ShareState {
        if (text.isBlank()) return ShareState()
        val moment = readSharedMoment(text, app.clock(), zone())
        return ShareState(
            text = text,
            title = shareTitle(text),
            detectedAt = moment?.at,
            matchedText = moment?.matchedText,
        )
    }

    /** Seeds the form from the shared text, once. */
    fun load(text: String?) {
        if (text.isNullOrBlank() || mutable.value.text.isNotEmpty()) return
        mutable.value = seed(text)
    }

    fun setTitle(title: String) = mutable.update { it.copy(title = title) }

    fun setDetectedAt(at: Long) = mutable.update { it.copy(detectedAt = at) }

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
