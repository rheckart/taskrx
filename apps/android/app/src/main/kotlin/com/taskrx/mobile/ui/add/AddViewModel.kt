package com.taskrx.mobile.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.parser.ReadPhraseOptions
import com.taskrx.core.parser.ReminderDraft
import com.taskrx.core.parser.isSavableDraft
import com.taskrx.core.parser.readPhrase
import com.taskrx.core.parser.withKind
import com.taskrx.core.parser.withName
import com.taskrx.mobile.AppContainer
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.components.ToastRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything that differs between the two Kinds, said once: a Kind is a column in a table, not a branch. */
data class KindCopy(
    val namePlaceholder: String,
    val notesLabel: String,
    val notesPlaceholder: String,
    val saveButton: String,
    val savedToast: String,
)

fun copyFor(kind: ReminderKind): KindCopy =
    when (kind) {
        ReminderKind.Medication ->
            KindCopy(
                namePlaceholder = "e.g. Metformin",
                notesLabel = "Instructions",
                notesPlaceholder = "e.g. with food",
                saveButton = "Save medication",
                savedToast = "Medication added",
            )
        ReminderKind.Task ->
            KindCopy(
                namePlaceholder = "e.g. Call the pharmacy",
                notesLabel = "Notes",
                notesPlaceholder = "e.g. ask about the repeat",
                saveButton = "Save task",
                savedToast = "Task added",
            )
    }

/** Said under a task form: a task is delivered, but not with the medication Tier's alarm. */
const val TASK_DELIVERY_NOTE: String =
    "Tasks show up on your timeline. They don\u2019t sound an alarm the way a medication does."

val EXAMPLE_PHRASES: List<String> =
    listOf(
        "atorvastatin 20mg every morning with food",
        "call pharmacy friday 2pm",
        "metformin 500mg twice daily 30 pills",
    )

/**
 * The words the parser itself recognises, so the button says back what you could have
 * typed: "urgent" reads as Critical, "important" as High, and a chip reading "Critical"
 * would show someone who typed "urgent" a word they did not use.
 */
fun priorityLabel(priority: Priority): String =
    when (priority) {
        Priority.Critical -> "Urgent"
        Priority.High -> "Important"
        Priority.Normal -> "Normal"
        Priority.Low -> "Low"
    }

/** Whether the phrase named any part of the supply picture. */
fun namesSupply(draft: ReminderDraft): Boolean =
    draft.pillsRemaining != null || draft.pillsPerDose != null || draft.refillReminderDays != null

data class AddState(
    val phrase: String = "",
    /** Null while the person is still typing the phrase. */
    val draft: ReminderDraft? = null,
    /** Whether [draft] is what the parser read, or the blank one it fell back to. */
    val understood: Boolean = false,
    /**
     * Whether to offer the supply boxes for a medication the phrase said nothing about.
     * A count the parser missed is as misread as one it got wrong, so there has to be a
     * way to it; a form that opens with three empty boxes is a different screen.
     */
    val supplyOpen: Boolean = false,
    val saving: Boolean = false,
    /** Written; the screen leaves. Stays set so a second tap cannot write a second row on the way out. */
    val saved: Boolean = false,
    val error: String? = null,
) {
    val canRead: Boolean get() = phrase.trim().isNotEmpty()
    val canSave: Boolean get() = draft != null && isSavableDraft(draft) && !saving && !saved
}

/**
 * Type a phrase, get the right Reminder. Mirrors archive/expo-rn:apps/mobile/app/(app)/add.tsx.
 *
 * The engine is :core's `readPhrase`; this adds no grammar. It collects a phrase, hands
 * it over, holds what came back as an editable draft, and writes what the person agreed
 * to. Every rule it appears to apply (which Kind a phrase is, what a failed read falls
 * back to, what a Kind correction may not carry across, whether a draft can be saved) is
 * a :core function with a case pinning it.
 *
 * Nothing is saved until it has been seen, and it never dead-ends: `readPhrase` returns a
 * draft even for the inputs the parser refuses, so "I could not read that" is a message
 * above a form that is already open. It works with no signal; the parse is pure.
 */
class AddViewModel(
    private val app: AppContainer,
) : ViewModel() {
    private val mutable = MutableStateFlow(AddState())
    val state: StateFlow<AddState> = mutable

    fun setPhrase(phrase: String) = mutable.update { it.copy(phrase = phrase) }

    fun usePhrase(example: String) = setPhrase(example)

    /**
     * The clock is read per press, and that is right rather than one captured with the
     * phrase: it anchors an `every N hours` schedule, which means "starting now".
     */
    fun read() {
        val reading = readPhrase(mutable.value.phrase, ReadPhraseOptions(now = app.clock()))
        mutable.update {
            it.copy(draft = reading.draft, understood = reading.understood, supplyOpen = namesSupply(reading.draft), error = null)
        }
    }

    /** Back to the phrase, with the phrase still in the box. */
    fun editPhrase() = mutable.update { it.copy(draft = null) }

    private fun patch(change: (ReminderDraft) -> ReminderDraft) =
        mutable.update { state -> state.draft?.let { state.copy(draft = change(it)) } ?: state }

    /** What a task may not carry is `withKind`'s to drop; closing the supply panel is presentation. */
    fun setKind(kind: ReminderKind) {
        patch { withKind(it, kind) }
        if (kind == ReminderKind.Task) mutable.update { it.copy(supplyOpen = false) }
    }

    /** Through `withName`, so typing over a name picked from RxNorm takes the concept id with it. */
    fun setName(name: String) = patch { withName(it, name) }

    fun setDosage(text: String) = patch { it.copy(dosage = text.takeUnless { t -> t.isBlank() }) }

    fun setInstructions(text: String) = patch { it.copy(instructions = text.takeUnless { t -> t.isBlank() }) }

    fun setPriority(priority: Priority) = patch { it.copy(priority = priority) }

    fun setSchedule(schedule: ScheduleTimes) = patch { it.copy(schedule = schedule) }

    fun setPillsRemaining(value: Double?) = patch { it.copy(pillsRemaining = value) }

    fun setPillsPerDose(value: Double?) = patch { it.copy(pillsPerDose = value) }

    fun setRefillReminderDays(value: Int?) = patch { it.copy(refillReminderDays = value) }

    fun openSupply() = mutable.update { it.copy(supplyOpen = true) }

    fun save(): Job =
        viewModelScope.launch {
            val current = mutable.value
            val draft = current.draft ?: return@launch
            if (current.saving || current.saved || !isSavableDraft(draft)) return@launch
            mutable.update { it.copy(saving = true, error = null) }
            try {
                val userId = app.session.userId
                // Trimmed at the boundary rather than on every keystroke, so a space
                // typed between two words is not eaten as it is typed.
                app.reminders.create(
                    CreateReminderInput(
                        userId = userId,
                        kind = draft.kind,
                        name = draft.name.trim(),
                        schedule = draft.schedule,
                        priority = draft.priority,
                        rxcui = draft.rxcui,
                        dosage = draft.dosage?.trim(),
                        instructions = draft.instructions?.trim(),
                        pillsRemaining = draft.pillsRemaining,
                        pillsPerDose = draft.pillsPerDose,
                        refillReminderDays = draft.refillReminderDays,
                    ),
                )
                app.notifications.rescheduleAfterWrite(userId)
                app.toaster.show(ToastRequest(copyFor(draft.kind).savedToast, ToastKind.Success))
                mutable.update { it.copy(saving = false, saved = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(saving = false, error = e.message ?: "Save failed") }
                app.toaster.show(ToastRequest("Couldn't save — will retry on next sync", ToastKind.Error))
            }
        }
}
