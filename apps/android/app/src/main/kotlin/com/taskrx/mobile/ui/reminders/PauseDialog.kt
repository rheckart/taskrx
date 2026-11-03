package com.taskrx.mobile.ui.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.Reminder
import com.taskrx.mobile.format.formatDay
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import java.time.ZoneOffset

private enum class Picking { None, Start, Resume }

/**
 * Pause [reminder]: from now or from a later day, until a day the person names or until they
 * resume. Both days are optional, so "stop today, I'll tell you when" is two taps.
 *
 * The dates are kept as the date picker gave them (UTC midnight of the tapped day) and only
 * turned into instants by [resolvePause], which also says when the two contradict.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseDialog(reminder: Reminder, now: Long, onConfirm: (PauseChoice) -> Unit, onDismiss: () -> Unit) {
    var start by rememberSaveable { mutableStateOf<Long?>(null) }
    var resume by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by rememberSaveable { mutableStateOf(Picking.None) }
    val choice = resolvePause(start, resume, now)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause ${reminder.name}?") },
        text = {
            Column {
                Text("It won't remind you while it is paused, and nothing is counted as missed. What you have recorded is kept.")
                FieldLabel("Pause from", Modifier.padding(top = 16.dp))
                DayButton("Pause from", start?.let(::dayText) ?: "Now") { picking = Picking.Start }
                FieldLabel("Start again on", Modifier.padding(top = 12.dp))
                DayButton("Start again on", resume?.let(::dayText) ?: "When I resume it") { picking = Picking.Resume }
                if (choice == null) {
                    ErrorText("Start again after the day it is paused from.")
                } else {
                    HintText("Reminders come back on the morning of the day you pick.")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = choice != null, onClick = { choice?.let(onConfirm) }) { Text("Pause") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking != Picking.None) {
        val current = if (picking == Picking.Start) start else resume
        val state = rememberDatePickerState(initialSelectedDateMillis = current)
        DatePickerDialog(
            onDismissRequest = { picking = Picking.None },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        if (picking == Picking.Start) start = state.selectedDateMillis else resume = state.selectedDateMillis
                        picking = Picking.None
                    },
                ) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Clearing is how "now" and "when I resume it" are chosen again.
                    if (picking == Picking.Start) start = null else resume = null
                    picking = Picking.None
                }) { Text(if (picking == Picking.Start) "Use now" else "No date") }
            },
        ) { DatePicker(state) }
    }
}

@Composable
private fun DayButton(label: String, shown: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = "$label, $shown" },
    ) { Text(shown) }
}

/** The picker's UTC-midnight answer, named by the calendar day it stands for. */
private fun dayText(dateMillis: Long): String = formatDay(dateMillis, ZoneOffset.UTC)
