package com.taskrx.mobile.ui.forms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.ChoiceRow
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText

/**
 * Schedule picker for the reminder forms. Mirrors archive/expo-rn:apps/mobile SchedulePicker.tsx.
 *
 * It speaks :core's `ScheduleTimes` directly, one value discriminated by its own type, and
 * all five variants are editable:
 *   - daily: a list of HH:MM slots
 *   - weekly: a set of weekdays, and the same slots on each
 *   - interval: an hours field and a start instant
 *   - once: a single instant (ADR 0006: an instant, never a bare date)
 *   - none: nothing to edit; the reminder goes to the Backlog
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
                    hint = "Format: YYYY-MM-DDTHH:MM (local time). This reminds you once, then it is done.",
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
 * The list of "HH:MM" slots, shared by the two variants that carry one: the editing, the
 * validation hint and the add/remove affordances are written once.
 */
@Composable
private fun TimesEditor(times: List<String>, onChange: (List<String>) -> Unit) {
    FieldLabel("Reminder times (HH:MM, 24h)")
    if (times.isEmpty()) {
        HintText("No times yet — tap “Add time” below.")
    }
    times.forEachIndexed { index, time ->
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = time,
                onValueChange = { next -> onChange(times.mapIndexed { i, t -> if (i == index) next else t }) },
                modifier = Modifier.width(120.dp),
                singleLine = true,
                label = { Text("Time ${index + 1}") },
                placeholder = { Text("HH:MM") },
                isError = !isValidTimeInput(time),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(
                onClick = { onChange(times.filterIndexed { i, _ -> i != index }) },
                modifier = Modifier.semantics { contentDescription = "Remove time ${index + 1}" },
            ) { Text("Remove", color = TaskRxColors.Error) }
        }
        if (!isValidTimeInput(time)) ErrorText("Use HH:MM (00:00–23:59)")
    }
    TextButton(onClick = { onChange(times + "09:00") }) { Text("+ Add time") }
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
                        if (on) TaskRxColors.Accent else androidx.compose.ui.graphics.Color(0xFFCCCCCC),
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
            "Format: YYYY-MM-DDTHH:MM (local time). Reminders fire every ${value.hours} hour${if (value.hours == 1) "" else "s"} from this start.",
        value = value.startAt,
        onChange = { onChange(intervalTimes(value.hours, it)) },
        now = now,
    )
}

/**
 * A local wall-clock instant, entered as text. Shared by the two variants that carry one,
 * so the format, the parse and the "that is in the past" warning are written once.
 *
 * The text is held here and only a valid instant reaches the caller, so a half-typed date
 * is not snapped back under the person's cursor.
 */
@Composable
private fun InstantField(label: String, hint: String, value: Long, onChange: (Long) -> Unit, now: () -> Long) {
    var text by rememberSaveable { mutableStateOf(instantToInput(value)) }
    val parsed = remember(text) { inputToInstant(text) }
    FieldLabel(label)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            inputToInstant(it)?.let(onChange)
        },
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        singleLine = true,
        placeholder = { Text("YYYY-MM-DDTHH:MM") },
        isError = parsed == null,
    )
    if (parsed == null) {
        ErrorText("Use YYYY-MM-DDTHH:MM.")
    } else if (value <= now()) {
        ErrorText("That is in the past — nothing will fire for it.")
    }
    HintText(hint)
}
