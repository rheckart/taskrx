package com.taskrx.mobile.ui.backlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.mobile.format.formatDayTime
import com.taskrx.mobile.format.kindLabel
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.forms.ScheduleType
import com.taskrx.mobile.ui.forms.SchedulePicker
import com.taskrx.mobile.ui.forms.ScheduleTypeChooser
import com.taskrx.mobile.ui.forms.defaultSchedule
import com.taskrx.mobile.ui.forms.scheduleTypeOf

data class BacklogActions(
    val onRefresh: () -> Unit,
    val onComplete: (Reminder) -> Unit,
    val onMoveToTimeline: (Reminder, ScheduleTimes) -> Unit,
)

/** The Backlog, drawn from [state]. Mirrors the render half of apps/mobile/app/(app)/backlog/index.tsx. */
@Composable
fun BacklogContent(state: BacklogState, actions: BacklogActions, modifier: Modifier = Modifier, now: () -> Long = System::currentTimeMillis) {
    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        skeletonRows = 4,
        loadingDescription = "Loading your backlog",
        modifier = modifier,
    ) {
        if (state.entries.isEmpty()) {
            item {
                EmptyState(
                    "Nothing in your backlog",
                    "Anything you add without a time waits here until you get to it.",
                    Modifier.fillParentMaxSize(),
                )
            }
        } else {
            items(state.entries, key = { it.reminder.id }) { entry ->
                BacklogRow(entry, busy = entry.reminder.id in state.busy, actions, now)
            }
        }
    }
}

@Composable
private fun BacklogRow(entry: BacklogEntry, busy: Boolean, actions: BacklogActions, now: () -> Long) {
    val reminder = entry.reminder
    // Held per row. Whether the editor is open survives rotation; the half-built time does not.
    var picking by rememberSaveable(reminder.id) { mutableStateOf(false) }
    var schedule by remember(reminder.id) { mutableStateOf<ScheduleTimes>(defaultSchedule(ScheduleType.Once, now())) }
    val lastDone = entry.lastCompleted?.completedAt?.let { "Last done ${formatDayTime(it)}" }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(reminder.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        reminder.dosage?.let { Text(it, Modifier.padding(top = 2.dp), color = TaskRxColors.Secondary) }
        Text(
            listOfNotNull(kindLabel(reminder.kind), lastDone).joinToString(" · "),
            Modifier.padding(top = 4.dp),
            color = TaskRxColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                // ADR 0001: the stored word is Completed for both Kinds; the person reads taken or done.
                label = if (reminder.kind == ReminderKind.Medication) "Taken" else "Done",
                onClick = { actions.onComplete(reminder) },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Complete ${reminder.name}" },
                enabled = !busy,
                color = TaskRxColors.CompleteButton,
            )
            PrimaryButton(
                label = if (picking) "Cancel" else "Give it a time",
                onClick = { picking = !picking },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Give ${reminder.name} a time" },
                color = TaskRxColors.SnoozeButton,
            )
        }

        if (picking) {
            HorizontalDivider(Modifier.padding(top = 12.dp), color = TaskRxColors.Divider)
            ScheduleTypeChooser(scheduleTypeOf(schedule), { schedule = it }, now)
            SchedulePicker(schedule, { schedule = it }, now = now)
            PrimaryButton(
                label = "Move to timeline",
                onClick = {
                    actions.onMoveToTimeline(reminder, schedule)
                    picking = false
                },
                modifier = Modifier.padding(top = 12.dp),
                enabled = canMoveToTimeline(schedule) && !busy,
                color = TaskRxColors.Accent,
            )
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}
