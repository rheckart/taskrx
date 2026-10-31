package com.taskrx.mobile.ui.catchup

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.catchup.CatchUpDay
import com.taskrx.core.catchup.catchUpDays
import com.taskrx.core.catchup.catchUpSlots
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The last bulk answer, kept so one tap can take it all back. */
data class CatchUpUndo(val message: String, val instanceIds: List<String>)

data class CatchUpState(
    val days: List<CatchUpDay> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val undo: CatchUpUndo? = null,
) {
    val total: Int get() = days.sumOf { it.rows.size }
}

/**
 * The Catch up screen: every Missed dose and task of the last week, answered for a day or for
 * all of them at once. Which rows those are is :core's `needsCatchUp`; the writes are
 * [com.taskrx.mobile.batch.BatchAnswers] and the undo is [com.taskrx.mobile.undo.UndoAnswers],
 * so a bulk answer is the same write as a slot's "Take all", one moment at a time.
 *
 * A Completed answer is stamped with the moment the dose was due: days later the person cannot
 * say when, and the scheduled time is the best guess the app has.
 */
class CatchUpViewModel(
    private val app: AppContainer,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("catchup", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
) : ViewModel() {
    private val mutable = MutableStateFlow(CatchUpState())
    val state: StateFlow<CatchUpState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    /** Marks [rows] Completed ([BatchAction.Take]) or Skipped. The screen offers no snooze. */
    fun answer(rows: List<TimelineRow>, action: BatchAction): Job =
        viewModelScope.launch {
            var changed = 0
            var failed = 0
            val ids = mutableListOf<String>()
            try {
                for ((scheduledTime, slot) in catchUpSlots(rows)) {
                    val takenAt = if (action == BatchAction.Take) scheduledTime else null
                    val result = app.batch.answer(action, scheduledTime, slot.map { it.reminderId }, takenAt)
                    changed += result.changed
                    failed += result.failed
                    ids += result.changedIds
                }
                val words = if (action == BatchAction.Take) "marked done" else "skipped"
                if (failed > 0) {
                    toast(ToastRequest("Couldn't answer $failed of ${rows.size}", ToastKind.Error))
                }
                mutable.update { it.copy(undo = if (ids.isEmpty()) null else CatchUpUndo("$changed $words", ids)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("a bulk answer failed", e)
                toast(ToastRequest("Couldn't answer those doses", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    /** Takes the last bulk answer back, all of it. */
    fun undo(): Job =
        viewModelScope.launch {
            val last = mutable.value.undo ?: return@launch
            mutable.update { it.copy(undo = null) }
            var refused = 0
            try {
                for (id in last.instanceIds) {
                    if (app.undo.undo(id) !is com.taskrx.mobile.undo.UndoResult.Reopened) refused++
                }
                toast(
                    if (refused == 0) {
                        ToastRequest("Taken back", ToastKind.Info)
                    } else {
                        ToastRequest("Couldn't take back $refused of ${last.instanceIds.size}", ToastKind.Error)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("undoing a bulk answer failed", e)
                toast(ToastRequest("Couldn't take that back", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        val now = app.clock()
        try {
            // Run the sweep first so a dose that ran out of time is already Missed, not still waiting.
            try {
                app.sweep.run(userId, now)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sweep before catch up failed", e)
            }
            val days = catchUpDays(app.catchUp.read(userId, now), zone())
            mutable.update { it.copy(days = days, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }
}
