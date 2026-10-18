package com.taskrx.mobile.ui.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.mobile.format.describeSchedule
import com.taskrx.mobile.format.formatDayTime
import com.taskrx.mobile.format.kindLabel
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold

data class RemindersActions(
    val onRefresh: () -> Unit,
    val onEdit: (Reminder) -> Unit,
    val onStop: (Reminder) -> Unit,
    val onSearchDrug: () -> Unit,
)

/** "Last taken: Never" / "Last done: Aug 4, 09:00": the Kind's word, ADR 0001. */
fun lastCompletedLine(entry: ReminderEntry): String {
    val word = if (entry.reminder.kind == ReminderKind.Medication) "taken" else "done"
    return "Last $word: ${entry.lastCompleted?.completedAt?.let { formatDayTime(it) } ?: "Never"}"
}

/** The Reminder list, drawn from [state]. Stopping asks first; it cannot be undone from here. */
@Composable
fun RemindersContent(state: RemindersState, actions: RemindersActions, modifier: Modifier = Modifier) {
    // The id, not the Reminder, so the dialog survives rotation.
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = state.entries.firstOrNull { it.reminder.id == confirming }?.reminder

    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        skeletonRows = 6,
        loadingDescription = "Loading your reminders",
        modifier = modifier,
    ) {
        item(key = "search") {
            TextButton(onClick = actions.onSearchDrug, Modifier.padding(horizontal = 8.dp)) { Text("+ Find a medication") }
            HorizontalDivider(color = TaskRxColors.Divider)
        }
        if (state.entries.isEmpty()) {
            item {
                EmptyState("No reminders yet", "Tap + Add on the Today screen to add your first.", Modifier.fillParentMaxSize())
            }
        } else {
            items(state.entries, key = { it.reminder.id }) { entry ->
                ReminderRow(entry, onEdit = { actions.onEdit(entry.reminder) }, onStop = { confirming = entry.reminder.id })
            }
        }
    }

    if (pending != null) {
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text("Stop ${pending.name}?") },
            text = { Text("It will stop reminding you. What you have already recorded is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    actions.onStop(pending)
                }) { Text("Stop", color = TaskRxColors.Error) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun ReminderRow(entry: ReminderEntry, onEdit: () -> Unit, onStop: () -> Unit) {
    val reminder = entry.reminder
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Edit", onClick = onEdit)
                .semantics(mergeDescendants = true) {
                    contentDescription = "${kindLabel(reminder.kind)}: ${reminder.name}, ${describeSchedule(reminder.schedule)}"
                },
        ) {
            Text(reminder.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            reminder.dosage?.let { Text(it, Modifier.padding(top = 2.dp), color = TaskRxColors.Secondary) }
            Text(
                "${kindLabel(reminder.kind)} · ${describeSchedule(reminder.schedule)}",
                Modifier.padding(top = 4.dp),
                color = TaskRxColors.BodyText,
            )
            Text(lastCompletedLine(entry), Modifier.padding(top = 4.dp), color = TaskRxColors.Accent, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "Edit ${reminder.name}" }) { Text("Edit") }
            TextButton(onClick = onStop, modifier = Modifier.semantics { contentDescription = "Stop ${reminder.name}" }) {
                Text("Stop", color = TaskRxColors.Error)
            }
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}
