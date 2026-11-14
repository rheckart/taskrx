package com.taskrx.mobile.ui.today

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
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.moveRefusal
import com.taskrx.mobile.format.formatDay
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.forms.TimeDialog
import com.taskrx.mobile.ui.forms.instantHourMinute
import com.taskrx.mobile.ui.forms.pickedInstant
import com.taskrx.mobile.ui.forms.pickerDateMillis
import java.time.ZoneOffset

private enum class Picking { None, Day }

/** What a move needs to say: the moment it starts from, and the Reminder's facts around it. */
data class MoveDraft(
    val row: com.taskrx.core.timeline.TimelineRow,
    val reminder: com.taskrx.core.domain.Reminder,
    val instances: List<com.taskrx.core.domain.ReminderInstance>,
)

/**
 * Move one Occurrence of a task to another day and time, leaving the Schedule alone.
 *
 * A move keeps the occurrence's wall-clock time unless the person changes it, so the dialog opens
 * on the moment already scheduled. Every refusal comes from `moveRefusal` in :core - including
 * "someone already answered this" and "it is too late to move" - and the button is simply off
 * while one applies, with the reason where the person is looking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveOccurrenceDialog(
    draft: MoveDraft,
    now: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val source = draft.row.scheduledTime
    val (hour, minute) = instantHourMinute(source)
    // The picker works in UTC-midnight days, as it does everywhere in this app.
    var day by rememberSaveable { mutableStateOf(pickerDateMillis(source)) }
    var time by rememberSaveable { mutableStateOf(hour to minute) }
    var picking by rememberSaveable { mutableStateOf(Picking.None) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }

    val target = pickedInstant(day, time.first, time.second)
    val refusal = moveRefusal(draft.reminder, source, target, now, draft.instances)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move ${draft.row.name}") },
        text = {
            Column {
                Text("Only this one. The schedule stays as it is, and the next one comes due as usual.")
                HintText("Now: ${formatFullDayTime(source)}", Modifier.padding(top = 12.dp))
                FieldLabel("Move to", Modifier.padding(top = 13.dp))
                DayButton("Move to day", formatDay(day, ZoneOffset.UTC)) { picking = Picking.Day }
                DayButton("Move to time", formatFullDayTime(target)) { pickingTime = true }
                HintText("Moves to ${formatFullDayTime(target)}")
                refusal?.let { ErrorText(reasonFor(it), Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = refusal == null, onClick = { onConfirm(target) }) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking == Picking.Day) {
        val state = rememberDatePickerState(initialSelectedDateMillis = day)
        DatePickerDialog(
            onDismissRequest = { picking = Picking.None },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let { day = it }
                        picking = Picking.None
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = Picking.None }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (pickingTime) {
        TimeDialog(
            hour = time.first,
            minute = time.second,
            onDismiss = { pickingTime = false },
            onConfirm = { h, m ->
                time = h to m
                pickingTime = false
            },
            title = "Move to",
        )
    }
}

/** The sentence the person can act on. Each is a different thing to do about it. */
private fun reasonFor(refusal: MoveRefusal): String =
    when (refusal) {
        MoveRefusal.NotATask -> "Only a task can be moved: a medication schedule is a clinical instruction."
        MoveRefusal.NotAfter -> "Pick a moment after the one it is on."
        MoveRefusal.InThePast -> "That moment has already passed."
        MoveRefusal.TooLate -> "It is too late to move this one. It has been due for more than two hours."
        MoveRefusal.AlreadyAnswered -> "This one has already been answered, and an answer is not rewritten."
        MoveRefusal.InProgress -> "You have already opened this one. Answer it first, then move the next."
        MoveRefusal.InAPause -> "It falls inside a pause, which comes first."
    }

@Composable
private fun DayButton(label: String, shown: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = "$label, $shown" },
    ) { Text(shown) }
}
