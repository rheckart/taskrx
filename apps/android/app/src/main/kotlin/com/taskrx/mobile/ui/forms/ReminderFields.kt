package com.taskrx.mobile.ui.forms

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.mobile.ui.add.copyFor
import com.taskrx.mobile.ui.components.FieldLabel

/**
 * The fields a Reminder is typed in, shared by the new and edit forms so the two cannot
 * drift: the name, the one optional detail that depends on the Kind (a medication's dosage,
 * a task's notes), and the Schedule.
 */
@Composable
fun ReminderFields(
    kind: ReminderKind,
    name: String,
    onName: (String) -> Unit,
    detail: String,
    onDetail: (String) -> Unit,
    schedule: ScheduleTimes,
    onSchedule: (ScheduleTimes) -> Unit,
    now: () -> Long,
    modifier: Modifier = Modifier,
) {
    val isTask = kind == ReminderKind.Task
    val copy = copyFor(kind)

    Column(modifier.fillMaxWidth()) {
        FieldLabel(if (isTask) "What do you need to do?" else "Name", Modifier.padding(top = 0.dp))
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            placeholder = { Text(copy.namePlaceholder) },
        )

        FieldLabel(if (isTask) "Notes (optional)" else "Dosage (optional)")
        OutlinedTextField(
            value = detail,
            onValueChange = onDetail,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            singleLine = true,
            placeholder = { Text(if (isTask) "e.g. ask about the repeat prescription" else "e.g. 500mg") },
        )

        FieldLabel("Schedule type")
        ScheduleTypeChooser(scheduleTypeOf(schedule), onSchedule, now)
        SchedulePicker(schedule, onSchedule, now = now)
    }
}
