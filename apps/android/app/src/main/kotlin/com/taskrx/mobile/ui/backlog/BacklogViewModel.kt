package com.taskrx.mobile.ui.backlog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.backlog.isBacklog
import com.taskrx.core.backlog.planBacklogCompletion
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.isWellFormedSchedule
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class BacklogEntry(val reminder: Reminder, val lastCompleted: ReminderInstance?)

data class BacklogState(
    val entries: List<BacklogEntry> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Reminders with a write in flight; a second tap on one of them is a double-tap and is ignored. */
    val busy: Set<String> = emptySet(),
)

/**
 * "Move to timeline" with no time is a no-op dressed as an action: `none` is a well-formed
 * Schedule, but choosing it again moves nothing anywhere.
 */
fun canMoveToTimeline(schedule: ScheduleTimes): Boolean = isWellFormedSchedule(schedule) && schedule !is NoneTimes

/**
 * The Backlog: commitments with no moment attached. Mirrors archive/expo-rn:apps/mobile/app/(app)/backlog.
 *
 * It is rendered from the Reminder rows themselves, which is what ADR 0002 requires of it:
 * a Backlog Reminder produces no Occurrences, so it is invisible to any query over
 * Instances until it is acted on. What counts as Backlog is :core's `isBacklog`, and what
 * Instance completing one produces is :core's `planBacklogCompletion` (minted at the moment
 * of the act, scheduled time equal to the completion time). This class persists that
 * decision and decides none of it.
 *
 * Completing one leaves the Reminder in the Backlog: the Instance records the act, and
 * nothing in the domain yet says whether an unscheduled commitment is one-shot or standing.
 * A person who is done with it for good stops it from the Reminders list.
 */
class BacklogViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(BacklogState())
    val state: StateFlow<BacklogState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            // Both Kinds, active only: a stopped reminder is not an outstanding commitment.
            val backlog = app.reminders.list(userId).filter(::isBacklog)
            val entries = backlog.map { BacklogEntry(it, app.instances.lastCompletedFor(userId, it.id)) }
            mutable.update { it.copy(entries = entries, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }

    fun complete(reminder: Reminder): Job =
        write(reminder, failure = "Couldn't record it", success = "Recorded") {
            val plan = planBacklogCompletion(reminder, app.clock())
            app.instances.create(
                CreateReminderInstanceInput(
                    userId = app.session.userId,
                    reminderId = plan.reminderId,
                    reminderKind = plan.reminderKind,
                    scheduledTime = plan.scheduledTime,
                    status = plan.status,
                    completedAt = plan.completedAt,
                ),
            )
        }

    fun moveToTimeline(reminder: Reminder, schedule: ScheduleTimes): Job =
        write(reminder, failure = "Couldn't schedule it", success = "Moved to your timeline") {
            require(canMoveToTimeline(schedule)) { "That is not a time to move it to" }
            app.reminders.update(reminder.id, UpdateReminderPatch(schedule = schedule))
            // It now has Occurrences, so what is armed must change with it.
            app.notifications.rescheduleAfterWrite(app.session.userId)
        }

    private fun write(reminder: Reminder, failure: String, success: String, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            if (reminder.id in mutable.value.busy) return@launch
            mutable.update { it.copy(busy = it.busy + reminder.id) }
            try {
                block()
                app.toaster.show(ToastRequest(success, ToastKind.Success))
                loads.withLock { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest(e.message ?: failure, ToastKind.Error))
            } finally {
                mutable.update { it.copy(busy = it.busy - reminder.id) }
            }
        }
}
