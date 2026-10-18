package com.taskrx.mobile.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.ChoiceRow
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import java.time.ZoneOffset

/**
 * Schedule picker for the reminder forms. Mirrors archive/expo-rn:apps/mobile SchedulePicker.tsx.
 *
 * It speaks :core's `ScheduleTimes` directly, one value discriminated by its own type, and
 * all five variants are editable:
 *   - daily: a list of time slots
 *   - weekly: a set of weekdays, and the same slots on each
 *   - interval: an hours field and a start instant
 *   - once: a single instant (ADR 0006: an instant, never a bare date)
 *   - none: nothing to edit; the reminder goes to the Backlog
 *
 * Times and dates are picked, never typed: no phone keyboard has a colon on its number pad, and
 * a typed "2026-08-04T09:30" asks for a hyphen, a letter T and a colon. The stored shapes are
 * unchanged ("HH:MM", an epoch instant).
 *
 * Whether the result can be saved is :core's `isWellFormedSchedule`; the hints here only
 * explain a field.
 */
@Composable
fun ScheduleTypeChooser(
    value: ScheduleType,
    onChange: (ScheduleTimes) -> Unit,
    now: () -> Long,
    modifier: Modifier = Modifier,
) {
    ChoiceRow(
        options = ScheduleType.entries.map { it to it.label },
        value = value,
        // Receives a fresh default for the chosen variant, not the bare type.
        onChange = { onChange(defaultSchedule(it, now())) },
        modifier = modifier,
    )
}

@Composable
fun SchedulePicker(
    value: ScheduleTimes,
    onChange: (ScheduleTimes) -> Unit,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    Column(modifier.fillMaxWidth()) {
        when (value) {
            is DailyTimes -> TimesEditor(value.times) { onChange(dailyTimes(it)) }
            is WeeklyTimes -> WeeklyPicker(value, onChange)
            is IntervalTimes -> IntervalPicker(value, onChange, now)
            is OnceTimes ->
                InstantField(
                    label = "Happens at",
                    hint = "This reminds you once, then it is done.",
                    value = value.at,
                    onChange = { onChange(onceTimes(it)) },
                    now = now,
                )
            NoneTimes ->
                HintText(
                    "No time at all. This goes to your Backlog, where you can complete it whenever you get to it — or give it a time later.",
                )
        }
    }
}

/**
 * The list of time slots, shared by the two variants that carry one: the picking, the
 * validation hint and the add/remove affordances are written once. A slot is shown the way the
 * device shows a time and stored as "HH:MM".
 */
@Composable
private fun TimesEditor(times: List<String>, onChange: (List<String>) -> Unit) {
    var editing by remember { mutableStateOf<Int?>(null) }
    FieldLabel("Reminder times")
    if (times.isEmpty()) {
        HintText("No times yet — tap “Add time” below.")
    }
    times.forEachIndexed { index, time ->
        val shown = timeInputToHourMinute(time)?.let { (hour, minute) -> clockLabel(hour, minute) } ?: time
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { editing = index },
                modifier = Modifier.semantics { contentDescription = "Time ${index + 1}, $shown" },
            ) { Text(shown) }
            TextButton(
                onClick = { onChange(times.filterIndexed { i, _ -> i != index }) },
                modifier = Modifier.semantics { contentDescription = "Remove time ${index + 1}" },
            ) { Text("Remove", color = TaskRxColors.Error) }
        }
        if (!isValidTimeInput(time)) ErrorText("That is not a time. Tap it to pick one.")
    }
    TextButton(onClick = { onChange(times + "09:00") }) { Text("+ Add time") }

    editing?.takeIf { it in times.indices }?.let { index ->
        // A stored time that is not one opens the picker on 09:00 rather than crashing it.
        val (hour, minute) = timeInputToHourMinute(times[index]) ?: (9 to 0)
        TimeDialog(
            hour = hour,
            minute = minute,
            onDismiss = { editing = null },
            onConfirm = { h, m ->
                editing = null
                onChange(times.mapIndexed { i, t -> if (i == index) hourMinuteToTimeInput(h, m) else t })
            },
        )
    }
}

/** "9:00 AM" or "09:00": a clock time as the device shows it. The date is a stand-in; only the time is read. */
private fun clockLabel(hour: Int, minute: Int): String = formatTime((hour * 60L + minute) * 60_000L, ZoneOffset.UTC)

/**
 * The time picker in a dialog, on the device's 12 or 24 hour clock. It opens on the dial; "Type it"
 * swaps in two boxes for the hour and the minute, for someone who would rather use the number pad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (hour: Int, minute: Int) -> Unit) {
    val state = rememberTimePickerState(hour, minute, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    var dial by rememberSaveable { mutableStateOf(true) }
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a time") },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        modeToggleButton = { TextButton(onClick = { dial = !dial }) { Text(if (dial) "Type it" else "Use dial") } },
    ) {
        if (dial) TimePicker(state) else TimeInput(state)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeeklyPicker(value: WeeklyTimes, onChange: (ScheduleTimes) -> Unit) {
    FieldLabel("Days")
    FlowRow(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WEEKDAY_LABELS.forEachIndexed { day, label ->
            val on = day in value.daysOfWeek
            // A checkbox, spoken by the full label ("Mon"), with its state.
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                color = if (on) TaskRxColors.Accent else androidx.compose.ui.graphics.Color.Transparent,
                contentColor = if (on) androidx.compose.ui.graphics.Color.White else androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                border =
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (on) TaskRxColors.Accent else TaskRxColors.Outline,
                    ),
                modifier =
                    Modifier.toggleable(
                        value = on,
                        role = Role.Checkbox,
                        onValueChange = { onChange(weeklyTimes(toggleDay(value.daysOfWeek, day), value.times)) },
                    ),
            ) {
                Text(label, Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
            }
        }
    }
    if (value.daysOfWeek.isEmpty()) ErrorText("Pick at least one day.")
    TimesEditor(value.times) { onChange(weeklyTimes(value.daysOfWeek, it)) }
}

@Composable
private fun IntervalPicker(value: IntervalTimes, onChange: (ScheduleTimes) -> Unit, now: () -> Long) {
    var hoursText by rememberSaveable { mutableStateOf(value.hours.toString()) }
    FieldLabel("Interval (hours)")
    OutlinedTextField(
        value = hoursText,
        onValueChange = {
            hoursText = it
            onChange(intervalTimes(parseIntervalHours(it), value.startAt))
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        singleLine = true,
        placeholder = { Text("8") },
        isError = parseIntervalHours(hoursText) == 0,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    InstantField(
        label = "First reminder at",
        hint =
            "Reminders fire every ${value.hours} hour${if (value.hours == 1) "" else "s"} from this start.",
        value = value.startAt,
        onChange = { onChange(intervalTimes(value.hours, it)) },
        now = now,
    )
}

/**
 * A local date and time, picked: the date first, then the time. Shared by the two variants that
 * carry one, so the picking and the "that is in the past" warning are written once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantField(label: String, hint: String, value: Long, onChange: (Long) -> Unit, now: () -> Long) {
    var step by remember { mutableStateOf(InstantStep.None) }
    var pickedDate by remember { mutableStateOf(0L) }
    val shown = formatFullDayTime(value)
    FieldLabel(label)
    OutlinedButton(
        onClick = { step = InstantStep.Date },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = "$label, $shown" },
    ) { Text(shown) }
    if (value <= now()) ErrorText("That is in the past — nothing will fire for it.")
    HintText(hint)

    when (step) {
        InstantStep.None -> Unit
        InstantStep.Date -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = pickerDateMillis(value))
            DatePickerDialog(
                onDismissRequest = { step = InstantStep.None },
                confirmButton = {
                    TextButton(
                        enabled = state.selectedDateMillis != null,
                        onClick = {
                            pickedDate = state.selectedDateMillis ?: return@TextButton
                            step = InstantStep.Time
                        },
                    ) { Text("Next") }
                },
                dismissButton = { TextButton(onClick = { step = InstantStep.None }) { Text("Cancel") } },
            ) { DatePicker(state) }
        }
        InstantStep.Time -> {
            val (hour, minute) = instantHourMinute(value)
            TimeDialog(
                hour = hour,
                minute = minute,
                onDismiss = { step = InstantStep.None },
                onConfirm = { h, m ->
                    step = InstantStep.None
                    onChange(pickedInstant(pickedDate, h, m))
                },
            )
        }
    }
}

private enum class InstantStep { None, Date, Time }
