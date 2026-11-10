package com.taskrx.mobile.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderAction
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderPhase
import com.taskrx.core.domain.reminderActions
import com.taskrx.core.domain.reminderPhase
import com.taskrx.mobile.AppContainer
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

/**
 * One Reminder as the list shows it: its lifecycle phase at the moment of reading, and the
 * actions that phase offers. [pause] and [actions] are derived from [phase], so a row cannot
 * say "Paused" while offering Pause, or offer Resume on a Reminder that is running.
 */
data class ReminderEntry(
    val reminder: Reminder,
    val lastCompleted: ReminderInstance?,
    val phase: ReminderPhase = ReminderPhase.Active,
) {
    /** The live pause, if the Reminder is paused: running now or waiting to start. */
    val pause: PauseWindow? get() = (phase as? ReminderPhase.Paused)?.window

    /** What this row may offer: Pause, Resume and/or Stop, decided by `:core`'s lifecycle. */
    val actions: Set<ReminderAction> get() = reminderActions(reminder, phase)
}

data class RemindersState(
    val entries: List<ReminderEntry> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
)

/**
 * Every live Reminder, of either Kind. Mirrors archive/expo-rn:apps/mobile `meds/index.tsx`, widened: that
 * list was the medications only, and it had no way to stop one. A Reminder is one aggregate
 * with a Kind axis, so this lists them together and the Kind is a word on the row.
 *
 * Stopping is the repository's soft delete (`active = false`), never a row delete: the
 * Instances cascade from the Reminder, and a stopped medication's recorded history still
 * has to be there for Adherence and for the history list. The armed notifications are
 * brought in line straight after, so a stopped Reminder cannot ring once more.
 *
 * Pausing is the other way to go quiet, and the one that comes back: the Reminder stays, with
 * a window in which it has no moments (`core/domain/Pause.kt`). Nothing in that window is
 * judged Missed, and the same reschedule takes its alarms down.
 */
class RemindersViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow(RemindersState())
    val state: StateFlow<RemindersState> = mutable

    private val loads = Mutex()

    fun refresh(pulled: Boolean = false): Job =
        viewModelScope.launch {
            if (pulled) mutable.update { it.copy(refreshing = true) }
            loads.withLock { load() }
        }

    private suspend fun load() {
        val userId = app.session.userId
        try {
            val now = app.clock()
            val entries =
                app.reminders.list(userId).map {
                    ReminderEntry(it, app.instances.lastCompletedFor(userId, it.id), reminderPhase(it, now))
                }
            mutable.update { it.copy(entries = entries, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "Unknown error") }
        } finally {
            mutable.update { it.copy(loading = false, refreshing = false) }
        }
    }

    fun stop(reminder: Reminder): Job =
        viewModelScope.launch {
            try {
                app.reminders.softDelete(reminder.id)
                app.notifications.rescheduleAfterWrite(app.session.userId)
                app.toaster.show(ToastRequest("Stopped ${reminder.name}", ToastKind.Success))
                loads.withLock { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest(e.message ?: "Couldn't stop it", ToastKind.Error))
            }
        }

    fun pause(reminder: Reminder, choice: PauseChoice): Job =
        change("Couldn't pause it") {
            app.reminders.pause(reminder.id, choice.from, choice.until)
            // A snooze already armed sits outside the Horizon the reschedule below reconciles,
            // so it would ring once more inside the pause. One for a pause that starts later stays.
            if (choice.from <= app.clock()) app.notifications.scheduler.cancelForReminder(reminder.id)
            "Paused ${reminder.name}"
        }

    /** Ends a running pause, or cancels one that has not started. */
    fun resume(reminder: Reminder): Job =
        change("Couldn't resume it") {
            app.reminders.resume(reminder.id)
            "Resumed ${reminder.name}"
        }

    private fun change(failure: String, write: suspend () -> String): Job =
        viewModelScope.launch {
            try {
                val message = write()
                app.notifications.rescheduleAfterWrite(app.session.userId)
                app.toaster.show(ToastRequest(message, ToastKind.Success))
                loads.withLock { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest(e.message ?: failure, ToastKind.Error))
            }
        }
}
