package com.taskrx.mobile.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.snooze.DEFAULT_SNOOZE_MINUTES
import com.taskrx.core.snooze.snoozeUntil
import com.taskrx.core.tasks.TaskRescheduleOutcome
import com.taskrx.core.tasks.TaskRescheduleRefusal
import com.taskrx.core.tasks.planTaskReschedule
import com.taskrx.core.tasks.plusDaysAtSameTime
import com.taskrx.core.tasks.tomorrowAtSameTime
import com.taskrx.mobile.format.formatDay
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.forms.TimeDialog
import com.taskrx.mobile.ui.forms.instantHourMinute
import com.taskrx.mobile.ui.forms.pickedInstant
import com.taskrx.mobile.ui.forms.pickerDateMillis
import java.time.ZoneOffset

private enum class Picking { None, Day }

/** What a reschedule dialog needs to say: the moment it starts from, and the Reminder's facts around it. */
data class MoveDraft(
    val row: com.taskrx.core.timeline.TimelineRow,
    val reminder: com.taskrx.core.domain.Reminder,
    val instances: List<com.taskrx.core.domain.ReminderInstance>,
)

/**
 * Reschedule one Occurrence of a task to another day and time, leaving the recurring Schedule alone.
 *
 * Uses `planTaskReschedule` in `:core` so open future occurrences move cleanly via an override (or
 * updating `OnceTimes`), while overdue, opened, and already-snoozed occurrences defer cleanly via
 * a `Snoozed` Instance. Quick-pick buttons offer common offsets alongside the day/time pickers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoveOccurrenceDialog(
    draft: MoveDraft,
    now: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
    snoozeMinutes: Int = DEFAULT_SNOOZE_MINUTES,
) {
    val source = draft.row.scheduledTime
    val currentMoment =
        draft.instances.firstOrNull { it.status == InstanceStatus.Snoozed }?.snoozedUntil
            ?: draft.row.snoozedUntil?.takeIf { draft.row.status == InstanceStatus.Snoozed }
            ?: source
    // Overdue tasks default to tomorrow at the same clock time so the dialog opens actionable.
    val initialTarget =
        if (currentMoment <= now) tomorrowAtSameTime(currentMoment, now) else currentMoment
    val (hour, minute) = instantHourMinute(initialTarget)
    // The picker works in UTC-midnight days, as it does everywhere in this app.
    var day by rememberSaveable(draft.row.reminderId, source) { mutableStateOf(pickerDateMillis(initialTarget)) }
    var time by rememberSaveable(draft.row.reminderId, source) { mutableStateOf(hour to minute) }
    var picking by rememberSaveable { mutableStateOf(Picking.None) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }

    val target = pickedInstant(day, time.first, time.second)
    val outcome = planTaskReschedule(draft.reminder, source, target, now, draft.instances)
    val refusal = (outcome as? TaskRescheduleOutcome.Refused)?.reason

    val presets =
        buildList {
            add("In $snoozeMinutes min" to snoozeUntil(now, snoozeMinutes))
            if (snoozeMinutes != 60) {
                add("In 1 hour" to snoozeUntil(now, 60))
            }
            val tomorrow = tomorrowAtSameTime(currentMoment, now)
            if (planTaskReschedule(draft.reminder, source, tomorrow, now, draft.instances) is TaskRescheduleOutcome.Allowed) {
                add("Tomorrow" to tomorrow)
            } else {
                add("+1 day" to plusDaysAtSameTime(currentMoment, 1))
            }
        }.filter { (_, instant) ->
            planTaskReschedule(draft.reminder, source, instant, now, draft.instances) is TaskRescheduleOutcome.Allowed
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reschedule ${draft.row.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Only this occurrence. The schedule stays as it is.")
                HintText("Now: ${formatFullDayTime(currentMoment)}", Modifier.padding(top = 8.dp))
                if (presets.isNotEmpty()) {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for ((label, instant) in presets) {
                            OutlinedButton(onClick = { onConfirm(instant) }) {
                                Text(label)
                            }
                        }
                    }
                }
                FieldLabel("Pick a day and time", Modifier.padding(top = 8.dp))
                DayButton("Reschedule to day", formatDay(day, ZoneOffset.UTC)) { picking = Picking.Day }
                DayButton("Reschedule to time", formatTime(target)) { pickingTime = true }
                HintText("Reschedules to ${formatFullDayTime(target)}")
                refusal?.let { ErrorText(reasonFor(it), Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = refusal == null, onClick = { onConfirm(target) }) { Text("Reschedule") }
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
            title = "Reschedule to",
        )
    }
}

/** The sentence the person can act on. Each is a different thing to do about it. */
private fun reasonFor(refusal: TaskRescheduleRefusal): String =
    when (refusal) {
        TaskRescheduleRefusal.NotATask -> "Only a task can be rescheduled: a medication schedule is a clinical instruction."
        TaskRescheduleRefusal.AlreadyAnswered -> "This one has already been answered, and an answer is not rewritten."
        TaskRescheduleRefusal.Unchanged -> "Pick a moment different from the one it is on."
        TaskRescheduleRefusal.InThePast -> "That moment has already passed."
        TaskRescheduleRefusal.NotAfter -> "Pick a moment after the one it is on."
        TaskRescheduleRefusal.InAPause -> "It falls inside a pause, which comes first."
    }

@Composable
private fun DayButton(label: String, shown: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = "$label, $shown" },
    ) { Text(shown) }
}
