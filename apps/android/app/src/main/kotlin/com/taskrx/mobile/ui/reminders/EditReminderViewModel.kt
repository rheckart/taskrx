package com.taskrx.mobile.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WatchedSource
import com.taskrx.core.domain.isWellFormedSchedule
import com.taskrx.core.domain.parseNoticeTerms
import com.taskrx.core.domain.withoutOverride
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.Change
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface EditState {
    data object Loading : EditState

    /** No live Reminder by that id on this device (stopped, or never here). */
    data object NotFound : EditState

    data class Editing(
        val reminder: Reminder,
        val name: String,
        /** A medication's dosage, or a task's notes: one text field, named by the Kind. */
        val detail: String,
        val schedule: ScheduleTimes,
        /** The sources a notice may be proposed from, for the "Notices about this" section. */
        val watchedSources: List<WatchedSource> = emptyList(),
        val saving: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    ) : EditState {
        val canSave: Boolean get() = name.trim().isNotEmpty() && isWellFormedSchedule(schedule) && !saving && !saved
    }
}

/**
 * Editing one Reminder: its name, its dosage (medication) or notes (task), and its
 * Schedule. Kind is not editable: it decides the Tier, and changing it would be a
 * different Reminder.
 *
 * A saved Schedule that now yields different Occurrences is followed by a reschedule, so
 * what is armed matches what is stored. Supply counts are edited on the Supplies screen,
 * where the projection that explains them lives.
 */
class EditReminderViewModel(private val app: AppContainer) : ViewModel() {
    private val mutable = MutableStateFlow<EditState>(EditState.Loading)
    val state: StateFlow<EditState> = mutable

    fun load(id: String?): Job =
        viewModelScope.launch {
            val reminder = id?.let { runCatching { app.reminders.get(it) }.getOrNull() }
            mutable.value =
                if (reminder == null || !reminder.active || reminder.userId != app.session.userId) {
                    EditState.NotFound
                } else {
                    EditState.Editing(
                        reminder = reminder,
                        name = reminder.name,
                        detail = (if (reminder.kind == ReminderKind.Medication) reminder.dosage else reminder.instructions).orEmpty(),
                        schedule = reminder.schedule,
                        watchedSources = runCatching { app.watchSources.list(app.session.userId) }.getOrDefault(emptyList()),
                    )
                }
        }

    private fun edit(change: (EditState.Editing) -> EditState.Editing) =
        mutable.update { if (it is EditState.Editing) change(it) else it }

    fun setName(name: String) = edit { it.copy(name = name) }

    fun setDetail(detail: String) = edit { it.copy(detail = detail) }

    fun setSchedule(schedule: ScheduleTimes) = edit { it.copy(schedule = schedule) }

    /**
     * Removes one moved occurrence.
     *
     * Kept apart from [save] because a move is not part of the form's subject: the form is about
     * the Schedule, and a move is a fact about one moment of it. Removing one is the escape hatch
     * for an override that a Schedule edit made inert, or one the person no longer wants.
     */
    fun removeOverride(from: Long): Job =
        viewModelScope.launch {
            val current = mutable.value as? EditState.Editing ?: return@launch
            try {
                val updated =
                    app.reminders.update(
                        current.reminder.id,
                        UpdateReminderPatch(overrides = current.reminder.overrides.withoutOverride(from)),
                    )
                app.notifications.rescheduleAfterWrite(app.session.userId)
                edit { it.copy(reminder = updated) }
                app.toaster.show(ToastRequest("Move removed", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest("Couldn't remove that move", ToastKind.Error))
            }
        }

    /**
     * Adds a rule: notices from [source] whose text mentions one of [termsText]'s words may be
     * proposed against this reminder. Persisted immediately, like removing a move, because it
     * is a fact about the reminder rather than a field of the form.
     */
    fun addNoticeRule(source: WatchedSource, termsText: String): Job =
        viewModelScope.launch {
            val current = mutable.value as? EditState.Editing ?: return@launch
            val rule = NoticeRule(source.kind, source.value, parseNoticeTerms(termsText))
            try {
                val updated =
                    app.reminders.update(
                        current.reminder.id,
                        UpdateReminderPatch(noticeRules = current.reminder.noticeRules + rule),
                    )
                edit { it.copy(reminder = updated) }
                app.toaster.show(ToastRequest("Notices rule added", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest("Couldn't add that rule", ToastKind.Error))
            }
        }

    /** Removes the rule at [index]. */
    fun removeNoticeRule(index: Int): Job =
        viewModelScope.launch {
            val current = mutable.value as? EditState.Editing ?: return@launch
            val remaining = current.reminder.noticeRules.filterIndexed { i, _ -> i != index }
            try {
                val updated =
                    app.reminders.update(
                        current.reminder.id,
                        UpdateReminderPatch(noticeRules = remaining),
                    )
                edit { it.copy(reminder = updated) }
                app.toaster.show(ToastRequest("Rule removed", ToastKind.Success))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.toaster.show(ToastRequest("Couldn't remove that rule", ToastKind.Error))
            }
        }

    fun save(): Job =
        viewModelScope.launch {
            val current = mutable.value as? EditState.Editing ?: return@launch
            if (!current.canSave) return@launch
            edit { it.copy(saving = true, error = null) }
            try {
                val detail = Change.To(current.detail.trim().ifEmpty { null })
                val isMedication = current.reminder.kind == ReminderKind.Medication
                app.reminders.update(
                    current.reminder.id,
                    UpdateReminderPatch(
                        name = current.name.trim(),
                        dosage = if (isMedication) detail else Change.Keep,
                        instructions = if (isMedication) Change.Keep else detail,
                        schedule = current.schedule,
                    ),
                )
                app.notifications.rescheduleAfterWrite(app.session.userId)
                app.toaster.show(ToastRequest("Saved", ToastKind.Success))
                edit { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                edit { it.copy(saving = false, error = e.message ?: "Save failed") }
                app.toaster.show(ToastRequest("Couldn't save — will retry on next sync", ToastKind.Error))
            }
        }
}
