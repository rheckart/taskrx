package com.taskrx.mobile.ui.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskrx.mobile.ui.components.Centered
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.forms.ReminderFields

/** The edit form, drawn from [state]. The same fields as the new-reminder form, started from what is stored. */
@Composable
fun EditReminderContent(
    state: EditState,
    vm: EditReminderViewModel,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    when (state) {
        EditState.Loading -> Centered(modifier) { Text("Loading…") }
        EditState.NotFound ->
            Centered(modifier) {
                Text("That reminder is not here any more.")
            }
        is EditState.Editing ->
            Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ReminderFields(
                    kind = state.reminder.kind,
                    name = state.name,
                    onName = vm::setName,
                    detail = state.detail,
                    onDetail = vm::setDetail,
                    schedule = state.schedule,
                    onSchedule = vm::setSchedule,
                    now = now,
                )
                state.error?.let { ErrorText("Save failed: $it", Modifier.padding(top = 16.dp)) }
                PrimaryButton("Save changes", vm::save, Modifier.padding(top = 24.dp), enabled = state.canSave, busy = state.saving)
            }
    }
}
