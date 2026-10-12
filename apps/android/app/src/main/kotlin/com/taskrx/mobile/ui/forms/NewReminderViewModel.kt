package com.taskrx.mobile.ui.forms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.isWellFormedSchedule
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.ui.add.copyFor
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewReminderState(
    val kind: ReminderKind,
    val name: String = "",
    /** The medication form's optional dosage. */
    val dosage: String = "",
    /** The task form's optional notes. */
    val notes: String = "",
    val schedule: ScheduleTimes,
    val saving: Boolean = false,
    /** Written; the screen leaves. Stays set so a second tap cannot write a second row on the way out. */
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = name.trim().isNotEmpty() && isWellFormedSchedule(schedule) && !saving && !saved
}

/**
 * The hand-entry forms for a medication and for a task. Mirrors archive/expo-rn:apps/mobile
 * `meds/new.tsx` and `tasks/new.tsx`.
 *
 * One class for two screens, with the Kind as a parameter, because the forms differ in
 * which optional field they offer and in their defaults, and in nothing else: a task has
 * no dosage, no RxCUI, no pill count and no refill threshold, and those are absent rather
 * than disabled. What the two share, the Schedule, is the picker.
 *
 * The default is `once` for a task (a one-off far more often than a medication is) and
 * `daily` for a medication, which is the common case for each.
 */
class NewReminderViewModel(
    private val app: AppContainer,
    kind: ReminderKind,
    /** A name already chosen, from a drug-search pick; empty for a blank form. */
    initialName: String = "",
    /** The RxNorm concept the name was picked from, stored with the Reminder. Medications only. */
    private val rxcui: String? = null,
) : ViewModel() {
    private val mutable =
        MutableStateFlow(
            NewReminderState(
                kind = kind,
                name = initialName,
                schedule = defaultSchedule(if (kind == ReminderKind.Task) ScheduleType.Once else ScheduleType.Daily, app.clock()),
            ),
        )
    val state: StateFlow<NewReminderState> = mutable

    fun setName(name: String) = mutable.update { it.copy(name = name) }

    fun setDosage(dosage: String) = mutable.update { it.copy(dosage = dosage) }

    fun setNotes(notes: String) = mutable.update { it.copy(notes = notes) }

    fun setSchedule(schedule: ScheduleTimes) = mutable.update { it.copy(schedule = schedule) }

    fun save(): Job =
        viewModelScope.launch {
            val current = mutable.value
            if (!current.canSave) return@launch
            mutable.update { it.copy(saving = true, error = null) }
            try {
                val userId = app.session.userId
                val dosage = current.dosage.trim().ifEmpty { null }
                val notes = current.notes.trim().ifEmpty { null }
                app.reminders.create(
                    CreateReminderInput(
                        userId = userId,
                        kind = current.kind,
                        name = current.name.trim(),
                        rxcui = if (current.kind == ReminderKind.Medication) rxcui else null,
                        schedule = current.schedule,
                        dosage = if (current.kind == ReminderKind.Medication) dosage else null,
                        instructions = if (current.kind == ReminderKind.Task) notes else null,
                    ),
                )
                app.notifications.rescheduleAfterWrite(userId)
                app.toaster.show(ToastRequest(copyFor(current.kind).savedToast, ToastKind.Success))
                mutable.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(saving = false, error = e.message ?: "Save failed") }
                app.toaster.show(ToastRequest("Couldn't save — will retry on next sync", ToastKind.Error))
            }
        }
}
