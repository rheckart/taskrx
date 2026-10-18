package com.taskrx.mobile.ui.add

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.parser.ReminderDraft
import com.taskrx.mobile.format.describeSchedule
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.ChoiceRow
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.NumberField
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.forms.SchedulePicker
import com.taskrx.mobile.ui.forms.ScheduleTypeChooser
import com.taskrx.mobile.ui.forms.scheduleTypeOf

private val KINDS = listOf(ReminderKind.Medication to "Medication", ReminderKind.Task to "Task")

/**
 * Add by phrase. Mirrors archive/expo-rn:apps/mobile/app/(app)/add.tsx.
 *
 * Nothing is saved until it has been seen: the parser is a small deterministic grammar,
 * so it is confidently wrong on phrases it does not cover, and a medication saved with a
 * misread dose is not a cosmetic failure. The draft is shown as a form and every field the
 * parser filled in is the field you correct, in place.
 *
 * The RxNorm lookup the Expo screen offered is absent: it needs the server's search action,
 * which arrives with sync (spec phase 5).
 */
@Composable
fun AddContent(
    state: AddState,
    vm: AddViewModel,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val draft = state.draft
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (draft == null) {
            PhraseStep(state, vm)
        } else {
            DraftStep(state, draft, vm, now)
        }
    }
}

@Composable
private fun PhraseStep(state: AddState, vm: AddViewModel) {
    FieldLabel("What do you need to remember?", Modifier.padding(top = 0.dp))
    OutlinedTextField(
        value = state.phrase,
        onValueChange = vm::setPhrase,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 88.dp),
        placeholder = { Text("e.g. atorvastatin 20mg every morning with food") },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
    )
    HintText("Say it however you like — a medication or something to do. You’ll see what was understood before anything is saved.")

    Column(Modifier.padding(top = 16.dp)) {
        for (example in EXAMPLE_PHRASES) {
            TextButton(
                onClick = { vm.usePhrase(example) },
                modifier = Modifier.semantics { contentDescription = "Use the example: $example" },
            ) { Text(example) }
        }
    }

    PrimaryButton("Review", vm::read, Modifier.padding(top = 24.dp), enabled = state.canRead)
}

@Composable
private fun DraftStep(state: AddState, draft: ReminderDraft, vm: AddViewModel, now: () -> Long) {
    val isMedication = draft.kind == ReminderKind.Medication
    val copy = copyFor(draft.kind)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(TaskRxColors.PanelBlue).padding(12.dp)) {
        Text("YOU TYPED", color = TaskRxColors.Secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(state.phrase.trim(), Modifier.padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = vm::editPhrase) { Text("Edit phrase") }
    }

    if (state.understood) {
        HintText("Here’s what that was understood as. Change anything that isn’t right.", Modifier.padding(top = 8.dp))
    } else {
        // Our words, not the parser's `reasons`: those tell a person how the grammar
        // failed, when the only thing they can act on is the form already open below.
        Notice(
            title = "That phrase couldn’t be read",
            body = "Nothing has been saved. Fill it in below instead — or go back and try a phrase with a name in it.",
            modifier = Modifier.padding(top = 16.dp),
        )
    }

    FieldLabel("Kind")
    ChoiceRow(KINDS, draft.kind, vm::setKind)

    FieldLabel("Name")
    OutlinedTextField(
        value = draft.name,
        onValueChange = vm::setName,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        singleLine = true,
        placeholder = { Text(copy.namePlaceholder) },
    )

    if (isMedication) {
        FieldLabel("Dosage")
        OutlinedTextField(
            value = draft.dosage ?: "",
            onValueChange = vm::setDosage,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            placeholder = { Text("e.g. 500mg") },
        )
    }

    FieldLabel(copy.notesLabel)
    OutlinedTextField(
        value = draft.instructions ?: "",
        onValueChange = vm::setInstructions,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        singleLine = true,
        placeholder = { Text(copy.notesPlaceholder) },
    )

    FieldLabel("Priority")
    ChoiceRow(Priority.entries.map { it to priorityLabel(it) }, draft.priority, vm::setPriority)

    FieldLabel("Schedule")
    Text(describeSchedule(draft.schedule), Modifier.padding(top = 4.dp, bottom = 8.dp), style = MaterialTheme.typography.bodyLarge)
    ScheduleTypeChooser(scheduleTypeOf(draft.schedule), vm::setSchedule, now)
    SchedulePicker(draft.schedule, vm::setSchedule, now = now)

    // Supply, for a medication. Shown outright when the phrase named any of the three,
    // because those are readings and a reading is what this screen is for putting right.
    // Behind one tap when it named none, so the parser's silence is appealable without the
    // form opening on three empty boxes.
    if (isMedication) {
        if (state.supplyOpen) {
            NumberField("Pills remaining", draft.pillsRemaining, vm::setPillsRemaining)
            NumberField("Pills per dose", draft.pillsPerDose, vm::setPillsPerDose)
            NumberField("Remind me to refill this many days ahead", draft.refillReminderDays, onChange = { vm.setRefillReminderDays(it?.toInt()) })
        } else {
            TextButton(onClick = vm::openSupply, Modifier.padding(top = 8.dp)) { Text("Add supply and refill details") }
        }
    }

    // Said out loud rather than dropped. The parser never rejects a phrase, it parses
    // around what it cannot place, so without this a word with no effect looks the same as
    // one that had.
    if (draft.unparsedTokens.isNotEmpty()) {
        Notice(
            title = "Not used: ${draft.unparsedTokens.joinToString(", ")}",
            body = "Those words weren’t understood, so nothing above came from them. Add them to the name or the notes if they matter.",
            modifier = Modifier.padding(top = 20.dp),
        )
    }

    if (draft.kind == ReminderKind.Task) {
        HintText(TASK_DELIVERY_NOTE, Modifier.padding(top = 20.dp))
    }

    state.error?.let { ErrorText("Save failed: $it", Modifier.padding(top = 16.dp)) }

    PrimaryButton(copy.saveButton, vm::save, Modifier.padding(top = 24.dp), enabled = state.canSave, busy = state.saving)
}

@Composable
private fun Notice(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TaskRxColors.TaskTint)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(title, color = TaskRxColors.Task, fontWeight = FontWeight.Bold)
        Text(body, Modifier.padding(top = 4.dp), color = TaskRxColors.WarnText, fontSize = 13.sp)
    }
}
