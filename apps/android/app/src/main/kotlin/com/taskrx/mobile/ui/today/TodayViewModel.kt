package com.taskrx.mobile.ui.today

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.timeline.ProjectTimelineInput
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.TimelineWindow
import com.taskrx.core.timeline.projectTimeline
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.InstanceFilter
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
)

/**
 * The Today timeline. Mirrors apps/mobile/app/(app)/index.tsx.
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
            if (mutable.value.dayOffset == offset) mutable.update { it.copy(rows = rows, error = null) }
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
