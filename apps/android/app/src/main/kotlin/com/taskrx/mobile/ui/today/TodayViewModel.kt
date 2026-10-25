package com.taskrx.mobile.ui.today

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.time.takenAtOrNull
import com.taskrx.core.undo.UNDOABLE_STATUSES
import com.taskrx.core.undo.isScheduledOccurrence
import com.taskrx.mobile.format.undoToast
import com.taskrx.mobile.undo.UndoResult
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.timeline.ProjectTimelineInput
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.TimelineWindow
import com.taskrx.core.timeline.projectTimeline
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.InstanceFilter
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

data class TodayState(
    /** 0 is today, -1 yesterday. Never positive: see [TodayViewModel.nextDay]. */
    val dayOffset: Int = 0,
    /** Local midnight of the day shown, for naming it. */
    val windowStart: Long,
    val rows: List<TimelineRow> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Show only what is still to do. The rows are all kept, so turning it off needs no re-read. */
    val hideCompleted: Boolean = false,
    /** The Instances whose answer can be taken back from the row (see [TodayViewModel.undo]). */
    val undoable: Set<String> = emptySet(),
) {
    /** What the list draws: every row, or all but the completed ones. */
    val visibleRows: List<TimelineRow>
        get() = if (hideCompleted) rows.filter { it.status != InstanceStatus.Completed } else rows
}

/**
 * The Today timeline. Mirrors archive/expo-rn:apps/mobile/app/(app)/index.tsx.
 *
 * Every rule about what the day contains lives in :core's `projectTimeline`. This class
 * does three things only: pick the window, read the two tables, and hand what comes back
 * to the screen. If a decision about ordering, deduplication or what counts as "next"
 * ever appears below, it is in the wrong file.
 *
 * The one thing it does beyond read-and-project is run the missed sweep, so a moment that
 * has run out of time reads as Missed here rather than as still waiting for an answer.
 * That is an ordering, not a decision, and the ordering is deliberate: the day is shown
 * first and re-projected after, so a long absence never leaves someone looking at a
 * skeleton while hundreds of rows are written.
 */
class TodayViewModel(
    private val app: AppContainer,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** The missed sweep: `(userId, now)` to how many it recorded. A parameter so a test can make it fail. */
    private val sweep: suspend (String, Long) -> Int = app.sweep::run,
    private val log: (String, Throwable) -> Unit = { what, e -> Log.e("today", what, e) },
    private val toast: (ToastRequest) -> Unit = app.toaster::show,
    /** Takes an answer back, by Instance id. A parameter so a test can make it fail. */
    private val undoAnswer: suspend (String) -> UndoResult = app.undo::undo,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(TodayState(windowStart = localDayBounds(app.clock(), 0, zone()).start))
    val state: StateFlow<TodayState> = mutable

    /** Loads are serialized: a second one waits for the first, so the sweep never overlaps itself here. */
    private val loads = Mutex()

    fun previousDay(): Job = moveTo(mutable.value.dayOffset - 1)

    /** Forward only as far as today: on a future day every row is ahead of now, so the first would be badged NEXT. */
    fun nextDay(): Job = moveTo(minOf(0, mutable.value.dayOffset + 1))

    fun today(): Job = moveTo(0)

    fun toggleHideCompleted() = mutable.update { it.copy(hideCompleted = !it.hideCompleted) }

    /**
     * Moves the time a completed dose was taken to [hour]:[minute] on the day it was
     * scheduled for. A time that has not happened yet is refused: a dose cannot have been
     * taken in the future.
     */
    fun changeTakenTime(row: TimelineRow, hour: Int, minute: Int): Job =
        viewModelScope.launch {
            val instanceId = row.instanceId ?: return@launch
            if (row.status != InstanceStatus.Completed) return@launch
            val taken = takenAtOrNull(row.scheduledTime, hour, minute, app.clock(), zone())
            if (taken == null) {
                toast(ToastRequest("That time hasn't happened yet", ToastKind.Error))
                return@launch
            }
            try {
                app.instances.changeCompletedAt(instanceId, taken)
                toast(ToastRequest("Time updated", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("changing the time taken failed", e)
                toast(ToastRequest("Couldn't change the time", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    /** Takes a row's answer back: a take, a skip or a snooze. The day is re-read afterwards. */
    fun undo(row: TimelineRow): Job =
        viewModelScope.launch {
            val instanceId = row.instanceId ?: return@launch
            try {
                toast(undoToast(undoAnswer(instanceId)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("undoing an answer failed", e)
                toast(ToastRequest("Couldn't take that back", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    /**
     * Takes, snoozes or skips every dose in [rows], which share one moment. Which rows form
     * a batch is `batchSlots` in :core; this only reports how it went and re-reads the day.
     * [hour] and [minute], for a Take, are when the doses were taken; without them it is now.
     */
    fun answerSlot(rows: List<TimelineRow>, action: BatchAction, hour: Int? = null, minute: Int? = null): Job =
        viewModelScope.launch {
            val first = rows.firstOrNull() ?: return@launch
            // A time only means something for a Take; the screen asks for one when a dose was missed.
            var takenAt: Long? = null
            if (action == BatchAction.Take && hour != null && minute != null) {
                takenAt = takenAtOrNull(first.scheduledTime, hour, minute, app.clock(), zone())
                if (takenAt == null) {
                    toast(ToastRequest("That time hasn't happened yet", ToastKind.Error))
                    return@launch
                }
            }
            try {
                val result = app.batch.answer(action, first.scheduledTime, rows.map { it.reminderId }, takenAt)
                toast(
                    if (result.failed > 0) {
                        ToastRequest("Couldn't answer ${result.failed} of ${rows.size}", ToastKind.Error)
                    } else {
                        val words =
                            when (action) {
                                BatchAction.Take -> "taken"
                                BatchAction.Skip -> "skipped"
                                BatchAction.Snooze -> "snoozed ${app.settings.snoozeMinutes.value} minutes"
                            }
                        ToastRequest("${result.changed} doses $words", if (action == BatchAction.Snooze) ToastKind.Info else ToastKind.Success)
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("answering a slot failed", e)
                toast(ToastRequest("Couldn't answer those doses", ToastKind.Error))
            }
            loads.withLock { load() }
        }

    private fun moveTo(offset: Int): Job {
        if (offset == mutable.value.dayOffset) return Job().also { it.complete() }
        mutable.update { it.copy(dayOffset = offset, loading = true) }
        return refresh()
    }

    /** Re-reads the day. Called on every resume, which also picks up rows a background pull merged. */
    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        val now = app.clock()
        val offset = mutable.value.dayOffset
        val window = localDayBounds(now, offset, zone())
        mutable.update { it.copy(windowStart = window.start) }

        suspend fun project() {
            // No Kind filter: the day is both Kinds interleaved. `includeInactive`
            // because a stopped reminder's recorded Instances are still part of the day;
            // which of them predict new Occurrences is the projection's decision.
            val reminders = app.reminders.list(userId, includeInactive = true)
            val instances = app.instances.list(InstanceFilter(userId, since = window.start, until = window.end))
            val rows =
                projectTimeline(ProjectTimelineInput(reminders, instances, TimelineWindow(window.start, window.end), now, zone()))
            // A different day was asked for while this one was reading; its load follows.
            // Which answered rows offer Undo: an answer on a moment the Schedule predicts. An extra
            // dose or a Backlog completion would reopen into a miss that never was (`undoVerdict`).
            val byId = reminders.associateBy { it.id }
            val undoable =
                rows.mapNotNullTo(HashSet()) { row ->
                    val id = row.instanceId ?: return@mapNotNullTo null
                    val reminder = byId[row.reminderId] ?: return@mapNotNullTo null
                    id.takeIf { row.status in UNDOABLE_STATUSES && isScheduledOccurrence(reminder, row.scheduledTime, zone()) }
                }
            if (mutable.value.dayOffset == offset) mutable.update { it.copy(rows = rows, undoable = undoable, error = null) }
        }

        try {
            project()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            // Another day asked for while this one read: its load is queued and clears
            // loading itself, so this one must not reveal the old day under the new heading.
            mutable.update { if (it.dayOffset == offset) it.copy(loading = false, refreshing = false) else it }
        }

        // After the day is on screen, and the day is re-projected only if it recorded something.
        try {
            if (sweep(userId, now) > 0) project()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not surfaced: the timeline above is correct either way, and the sweep runs again on the next resume.
            log("sweep after timeline read failed", e)
        }
    }
}
