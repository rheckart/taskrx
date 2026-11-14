package com.taskrx.mobile.ui.notices

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A notice the person is turning into a task: the notice, and the moment they picked. */
data class NoticeTaskDraft(
    val notice: Notice,
    val at: Long,
)

data class NoticesState(
    val notices: List<Notice> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val draft: NoticeTaskDraft? = null,
    val saving: Boolean = false,
) {
    /** The notices still waiting for a decision: what the More menu's count adds up. */
    val unresolved: Int get() = notices.count { it.resolution == NoticeResolution.None }
}

/**
 * The Notices screen: outside messages a watched source admitted, and the two things a person
 * can do with one before extraction exists - make a task from it, or put it aside.
 *
 * No date is read from the sentence here. The person picks the moment, which is the whole
 * contract of this phase (the extractor is Phase 3's). Dismissing keeps the row and its
 * resolution; only the Settings screen's removal deletes one.
 */
class NoticesViewModel(
    private val app: AppContainer,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("notices", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
) : ViewModel() {
    private val mutable = MutableStateFlow(NoticesState())
    val state: StateFlow<NoticesState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    /** Opens the "new task from this notice" form, with a moment one day out at 09:00. */
    fun beginTask(notice: Notice) {
        val tomorrow = zone().let {
            Instant.ofEpochMilli(app.clock()).atZone(it).toLocalDate().plusDays(1)
        }
        val at =
            tomorrow.atStartOfDay(zone()).plusHours(9).toInstant().toEpochMilli()
        mutable.update { it.copy(draft = NoticeTaskDraft(notice, at)) }
    }

    fun setDraftAt(at: Long) = mutable.update { it.copy(draft = it.draft?.copy(at = at)) }

    fun cancelTask() = mutable.update { it.copy(draft = null) }

    /** Creates a one-off task at the moment the person picked, then settles the notice. */
    fun saveTask(): Job =
        viewModelScope.launch {
            val draft = mutable.value.draft ?: return@launch
            if (mutable.value.saving) return@launch
            mutable.update { it.copy(saving = true) }
            try {
                app.reminders.create(
                    CreateReminderInput(
                        userId = app.session.userId,
                        kind = ReminderKind.Task,
                        name = draft.notice.title.trim().ifEmpty { "From a notice" },
                        schedule = onceTimes(draft.at),
                        instructions = draft.notice.sentence,
                    ),
                )
                app.notices.resolve(draft.notice.id, NoticeResolution.Applied)
                app.notifications.rescheduleAfterWrite(app.session.userId)
                mutable.update { it.copy(draft = null) }
                toast(ToastRequest("Task added", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("creating a task from a notice failed", e)
                toast(ToastRequest("Couldn't add that task", ToastKind.Error))
            } finally {
                mutable.update { it.copy(saving = false) }
            }
            loads.withLock { load() }
        }

    /** Puts a notice aside without doing anything with it. */
    fun dismiss(notice: Notice): Job =
        viewModelScope.launch {
            try {
                app.notices.resolve(notice.id, NoticeResolution.Dismissed)
                toast(ToastRequest("Notice dismissed", ToastKind.Info))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("dismissing a notice failed", e)
                toast(ToastRequest("Couldn't dismiss that", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            val notices = app.notices.list(userId).sortedByDescending { it.receivedAt }
            mutable.update { it.copy(notices = notices, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }
}
