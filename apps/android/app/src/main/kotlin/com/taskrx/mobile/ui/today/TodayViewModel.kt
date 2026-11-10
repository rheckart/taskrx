package com.taskrx.mobile.ui.today

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.asneeded.takings
import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.isAnswerable
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
import com.taskrx.mobile.asneeded.AsNeededEntry
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.dose.RecordResult
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
    /** 0 is today, -1 yesterday. Positive only on the Tasks tab: see [TodayViewModel.nextDay]. */
    val dayOffset: Int = 0,
    /** Local midnight of the day shown, for naming it. */
    val windowStart: Long,
    val rows: List<TimelineRow> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Show only what is still to do. The rows are all kept, so turning it off needs no re-read. On to begin with: a day opens on what is left. */
    val hideCompleted: Boolean = true,
    /** The Instances whose answer can be taken back from the row (see [TodayViewModel.undo]). */
    val undoable: Set<String> = emptySet(),
    /** How many doses and tasks of the last week are Missed and unanswered: the banner to Catch up. */
    val catchUpCount: Int = 0,
    /** The tab open: the day is split by Kind, medications first. The projection stays Kind-neutral; only the screen filters. */
    val kind: ReminderKind = ReminderKind.Medication,
    /** Whether the person picked a tab. Until they do, the tab follows [defaultKind]. */
    val kindChosen: Boolean = false,
    /** Medications taken as needed, for the card at the top of the Medications tab. Not part of any day's rows. */
    val asNeeded: List<AsNeededEntry> = emptyList(),
) {
    /** The rows of the open tab, done or not. */
    val tabRows: List<TimelineRow>
        get() = rows.filter { it.kind == kind }

    /** What the list draws: every row of the open tab, or all but the completed and skipped ones. */
    val visibleRows: List<TimelineRow>
        get() = if (hideCompleted) tabRows.filter { it.status != InstanceStatus.Completed && it.status != InstanceStatus.Skipped } else tabRows

    /**
     * Whether the day can move forward. A medication's day stops at today: on a future day every dose
     * is ahead of now, and the Occurrences it shows are guesses. A task is worth looking ahead for.
     */
    val canGoForward: Boolean
        get() = kind == ReminderKind.Task || dayOffset < 0

    /** How many rows of each Kind still want an answer, for the badge on its tab. */
    val toDoByKind: Map<ReminderKind, Int>
        get() = rows.filter { isAnswerable(it.status) }.groupingBy { it.kind }.eachCount()
}

private const val DAY_MS = 24 * 60 * 60_000L

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

    /** Forward only as far as today for medications; without limit for tasks. */
    fun nextDay(): Job = moveTo(mutable.value.dayOffset + 1)

    fun today(): Job = moveTo(0)

    fun selectKind(kind: ReminderKind): Job {
        mutable.update { it.copy(kind = kind, kindChosen = true) }
        // Medications do not look ahead: leaving a future day of tasks comes back to today.
        return if (kind == ReminderKind.Medication && mutable.value.dayOffset > 0) today() else Job().also { it.complete() }
    }

    /** Records a dose of an as-needed medication as taken now, and re-reads so its countdown starts. */
    fun takeAsNeeded(medication: Reminder): Job =
        viewModelScope.launch {
            try {
                if (app.doses.record(medication, app.clock()) == RecordResult.AlreadyRecorded) {
                    toast(ToastRequest("Already recorded at this time", ToastKind.Error))
                } else {
                    toast(ToastRequest("${medication.name} recorded as taken", ToastKind.Success))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("recording an as-needed dose failed", e)
                toast(ToastRequest("Couldn't record it", ToastKind.Error))
            }
            loads.withLock { load() }
        }

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
                // A dose of an as-needed medication starts its wait from when it was taken.
                app.notifications.rescheduleAfterWrite(app.session.userId)
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

    private fun moveTo(requested: Int): Job {
        val offset = if (mutable.value.canGoForward) requested else minOf(0, requested)
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
            val projected =
                projectTimeline(ProjectTimelineInput(reminders, instances, TimelineWindow(window.start, window.end), now, zone()))
            // On a day ahead every row is ahead of now, so the first would be badged NEXT: "do this now"
            // about something not due for a day.
            val rows = if (offset > 0) projected.map { it.copy(isNext = false) } else projected
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
            // Nobody with no medication to take should open on an empty Medications tab: it opens on
            // Tasks until they have one, or choose a tab themselves.
            val noMedications =
                reminders.none { it.kind == ReminderKind.Medication && it.active } &&
                    rows.none { it.kind == ReminderKind.Medication && isAnswerable(it.status) }
            val opensOn = if (noMedications) ReminderKind.Task else ReminderKind.Medication
            val asNeeded =
                reminders.filter { it.active && it.kind == ReminderKind.Medication && it.schedule is AsNeededTimes }.sortedBy { it.name.lowercase() }.let { waiting ->
                    if (waiting.isEmpty()) {
                        emptyList()
                    } else {
                        // Two days back, by the Instance's own moment: room for a time moved after the fact.
                        val recent = app.instances.list(InstanceFilter(userId, since = now - 2 * DAY_MS))
                        waiting.map { AsNeededEntry(it, it.schedule as AsNeededTimes, takings(recent, it.id)) }
                    }
                }
            if (mutable.value.dayOffset == offset) {
                mutable.update {
                    it.copy(rows = rows, undoable = undoable, asNeeded = asNeeded, error = null, kind = if (it.kindChosen) it.kind else opensOn)
                }
            }
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
            val missed = app.catchUp.read(userId, now).size
            mutable.update { it.copy(catchUpCount = missed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not surfaced: the timeline above is correct either way, and the sweep runs again on the next resume.
            log("sweep after timeline read failed", e)
        }
    }
}
