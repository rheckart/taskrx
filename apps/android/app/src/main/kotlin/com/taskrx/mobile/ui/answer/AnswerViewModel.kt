package com.taskrx.mobile.ui.answer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.core.snooze.rescheduleInstant
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.format.wordsFor
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the answer screen is showing. */
sealed interface AnswerState {
    data object Loading : AnswerState

    /** The link named no Reminder and moment: opened from somewhere other than the timeline or a notification. */
    data object InvalidLink : AnswerState

    data class Failed(val message: String) : AnswerState

    data class Loaded(
        val reminder: Reminder,
        val scheduledTime: Long,
        val instance: ReminderInstance,
        val action: Action = Action.Idle,
        /** Set once an answer has been recorded: when, for the confirmation card. */
        val recordedAt: Long? = null,
        /** An answer that failed to record. The buttons are live again. */
        val error: String? = null,
    ) : AnswerState
}

enum class Action { Idle, Completing, Skipping, Snoozing, Done }

/**
 * The answer surface: what became of one Occurrence, of either Kind. Mirrors
 * archive/expo-rn:apps/mobile/app/(app)/dose/log.tsx.
 *
 * Reached from a tap on the timeline and from a notification tap; both carry the same
 * pairing of Reminder and scheduled time. Nothing about it is medication-specific except
 * its words (see `wordsFor`), which is why a task is completed, skipped and snoozed
 * through exactly these buttons.
 *
 * Every answer is followed by a reschedule, so what is armed never lags what is stored.
 */
class AnswerViewModel(
    private val app: AppContainer,
    private val toast: (ToastRequest) -> Unit,
    /**
     * Arms the deferred reminder: `(reminderId, scheduledTime, snoozeUntil)` to whether
     * anything was armed. False only for a Reminder this device cannot find, which this
     * screen cannot reach (it refuses to open on that same lookup); the toast still branches
     * on it, because the one thing worse than an unhelpful message is promising a reminder
     * that was never armed.
     */
    private val armSnooze: suspend (String, Long, Long) -> Boolean = app.notifications.scheduler::snooze,
) : ViewModel() {
    private val mutable = MutableStateFlow<AnswerState>(AnswerState.Loading)
    val state: StateFlow<AnswerState> = mutable

    fun load(reminderId: String?, scheduledTime: Long?): Job =
        viewModelScope.launch {
            if (reminderId == null || scheduledTime == null) {
                mutable.value = AnswerState.InvalidLink
                return@launch
            }
            mutable.value =
                try {
                    open(reminderId, scheduledTime)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AnswerState.Failed(e.message ?: "Failed to load reminder")
                }
        }

    private suspend fun open(reminderId: String, scheduledTime: Long): AnswerState {
        val userId = app.session.userId
        val reminder =
            app.reminders.get(reminderId) ?: return AnswerState.Failed("This reminder no longer exists.")

        // One lookup, not two: the unanswered Instance for this exact pairing is the row
        // the buttons act on. Minting it here rather than at alarm time keeps the table a
        // record of what the person did and not a materialized copy of the schedule.
        //
        // "Unanswered" includes Snoozed: a snooze is a deferral, so coming back to the
        // same moment must find the row it already made. Reopening a moment already
        // Completed or Skipped mints a second, pending Instance beside the answered one
        // (issue #21, carried over from the Expo app); the timeline deduplicates on the
        // same pairing so it does not render twice.
        val instance =
            app.instances.findAnswerableForOccurrence(userId, reminderId, scheduledTime)
                ?: app.instances.create(
                    CreateReminderInstanceInput(
                        userId = userId,
                        reminderId = reminderId,
                        // Off the Reminder, never hardcoded: it decides whether the
                        // Instance counts toward Adherence.
                        reminderKind = reminder.kind,
                        scheduledTime = scheduledTime,
                    ),
                )
        return AnswerState.Loaded(reminder, scheduledTime, instance)
    }

    fun complete(): Job =
        answer(Action.Completing, failure = "Couldn't record it — will retry on next sync") { loaded ->
            val now = app.clock()
            app.instances.markCompleted(loaded.instance.id, now)
            toast(ToastRequest(wordsFor(loaded.reminder.kind).completed, ToastKind.Success))
            now
        }

    fun skip(): Job =
        answer(Action.Skipping, failure = "Couldn't record skip — will retry on next sync") { loaded ->
            app.instances.markSkipped(loaded.instance.id)
            toast(ToastRequest(wordsFor(loaded.reminder.kind).skipped, ToastKind.Success))
            app.clock()
        }

    /**
     * Writes the deferral down and then arms the deferred reminder. The row goes first,
     * so a failure to arm still leaves the deferral recorded (the row is what syncs).
     * One instant is decided here and used for both: two readings of the clock would let
     * the stored deferral and the armed alarm name different moments.
     */
    fun snooze(): Job {
        val minutes = app.settings.snoozeMinutes.value
        return putOff(Action.Snoozing, "Couldn't snooze reminder", { it + minutes * 60_000L }) { "Reminder snoozed $minutes minutes" }
    }

    /**
     * "Pick a time": the same deferral as [snooze], to a clock time later today. The person
     * chose a time, so the toast names it. A time that is not ahead of now writes nothing
     * and says so; the picker cannot tell the person it is behind them.
     */
    fun rescheduleTo(hour: Int, minute: Int): Job {
        val at = rescheduleInstant(app.clock(), hour, minute)
        if (at == null) {
            toast(ToastRequest("Pick a time later today", ToastKind.Error))
            return Job().also { it.complete() }
        }
        return putOff(Action.Snoozing, "Couldn't reschedule reminder", { at }) { "Reminder set for ${formatTime(at)}" }
    }

    private fun putOff(action: Action, failure: String, until: (now: Long) -> Long, armedMessage: () -> String): Job =
        answer(action, failure) { loaded ->
            val snoozeUntil = until(app.clock())
            app.instances.markSnoozed(loaded.instance.id, snoozeUntil)
            val armed = armSnooze(loaded.reminder.id, loaded.scheduledTime, snoozeUntil)
            toast(ToastRequest(if (armed) armedMessage() else "Put off for now", ToastKind.Info))
            app.clock()
        }

    private fun answer(action: Action, failure: String, write: suspend (AnswerState.Loaded) -> Long): Job =
        viewModelScope.launch {
            val loaded = mutable.value as? AnswerState.Loaded ?: return@launch
            // One answer at a time: a second tap while one is in flight is a double-tap.
            if (loaded.action != Action.Idle) return@launch
            mutable.value = loaded.copy(action = action, error = null)
            try {
                val at = write(loaded)
                // The write has happened; a reschedule failure must not read as one.
                app.notifications.rescheduleAfterWrite(app.session.userId)
                mutable.update { (it as AnswerState.Loaded).copy(action = Action.Done, recordedAt = at) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { (it as AnswerState.Loaded).copy(action = Action.Idle, error = e.message ?: "Failed to record it") }
                toast(ToastRequest(failure, ToastKind.Error))
            }
        }
}
