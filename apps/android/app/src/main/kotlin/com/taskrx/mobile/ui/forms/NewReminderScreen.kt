package com.taskrx.mobile.ui.forms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.ReminderKind
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.add.TASK_DELIVERY_NOTE
import com.taskrx.mobile.ui.add.copyFor

/**
 * The manual new-reminder form for either Kind. Mirrors apps/mobile `meds/new.tsx` and
 * `tasks/new.tsx`.
 *
 * All five Schedule variants are offered to both Kinds. `once` gives a medication a single
 * dose (a short course, a one-off injection), `none` puts it in the Backlog ("I have this,
 * I take it when I need it"), and `weekly` is what a task most often needs ("bins out on
 * Tuesday") and a weekly injection needs too.
 */
@Composable
fun NewReminderContent(
    state: NewReminderState,
    vm: NewReminderViewModel,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val isTask = state.kind == ReminderKind.Task
    val copy = copyFor(state.kind)

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        FieldLabel(if (isTask) "What do you need to do?" else "Name", Modifier.padding(top = 0.dp))
        OutlinedTextField(
            value = state.name,
            onValueChange = vm::setName,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            placeholder = { Text(copy.namePlaceholder) },
        )

        if (isTask) {
            FieldLabel("Notes (optional)")
            OutlinedTextField(
                value = state.notes,
                onValueChange = vm::setNotes,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("e.g. ask about the repeat prescription") },
            )
        } else {
            FieldLabel("Dosage (optional)")
            OutlinedTextField(
                value = state.dosage,
                onValueChange = vm::setDosage,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                singleLine = true,
                placeholder = { Text("e.g. 500mg") },
            )
        }

        FieldLabel("Schedule type")
        ScheduleTypeChooser(scheduleTypeOf(state.schedule), vm::setSchedule, now)
        SchedulePicker(state.schedule, vm::setSchedule, now = now)

        if (isTask) {
            HintText(TASK_DELIVERY_NOTE, Modifier.padding(top = 20.dp))
        }

        state.error?.let { ErrorText("Save failed: $it", Modifier.padding(top = 16.dp)) }

        PrimaryButton(copy.saveButton, vm::save, Modifier.padding(top = 24.dp), enabled = state.canSave, busy = state.saving)
    }
}
