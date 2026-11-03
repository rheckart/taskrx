package com.taskrx.mobile.ui.catchup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.mobile.format.formatDay
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.format.kindLabel
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.SectionHeader

/**
 * Every Missed dose and task of the last week, by day. Each row has its own Take (Done, for a
 * task) and Skip. A day holds its medications and its tasks apart, and the shortcut above each
 * answers only that part, so one tap never answers a task and a medication together.
 */
@Composable
fun CatchUpContent(
    state: CatchUpState,
    onRefresh: () -> Unit,
    onAnswer: (List<TimelineRow>, BatchAction) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        state.undo?.let { undo ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(undo.message, color = TaskRxColors.Secondary)
                TextButton(onClick = onUndo) { Text("Undo") }
            }
            HorizontalDivider(color = TaskRxColors.Divider)
        }
        ListScaffold(
            loading = state.loading,
            error = state.error,
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            skeletonRows = 6,
            loadingDescription = "Loading missed doses",
            modifier = Modifier.weight(1f),
        ) {
            if (state.days.isEmpty()) {
                item { EmptyState("All caught up", "Nothing from the last 7 days needs an answer.", Modifier.fillParentMaxSize()) }
            } else {
                item(key = "all") {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("${state.total} to answer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "An answer uses the time each one was due.",
                            Modifier.padding(top = 4.dp),
                            color = TaskRxColors.Secondary,
                        )
                    }
                    HorizontalDivider(color = TaskRxColors.Divider)
                }
                for (day in state.days) {
                    val dayName = formatDay(day.dayStart)
                    item(key = "day-${day.dayStart}") { SectionHeader(dayName) }
                    // Medications first: the ones that matter most are the first to be answered.
                    for (kind in listOf(ReminderKind.Medication, ReminderKind.Task)) {
                        val part = day.rows.filter { it.kind == kind }
                        if (part.isEmpty()) continue
                        item(key = "part-${day.dayStart}-${kind.name}") { PartBar(kind, part, dayName, onAnswer) }
                        items(part, key = { "${it.reminderId}-${it.scheduledTime}" }) { row ->
                            MissedRow(row, dayName, onTake = { onAnswer(listOf(row), BatchAction.Take) }, onSkip = { onAnswer(listOf(row), BatchAction.Skip) })
                        }
                    }
                }
            }
        }
    }
}

/** The label above a day's medications or tasks, with a shortcut for the lot when there are two or more. */
@Composable
private fun PartBar(kind: ReminderKind, rows: List<TimelineRow>, dayName: String, onAnswer: (List<TimelineRow>, BatchAction) -> Unit) {
    val medication = kind == ReminderKind.Medication
    val label = if (medication) "Medications" else "Tasks"
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (medication) TaskRxColors.Medication else TaskRxColors.Task)
        if (rows.size >= 2) {
            val take = if (medication) "Take all" else "Done all"
            Row {
                TextButton(
                    onClick = { onAnswer(rows, BatchAction.Take) },
                    modifier = Modifier.semantics { contentDescription = "$take ${rows.size} ${label.lowercase()} on $dayName" },
                ) { Text(take) }
                TextButton(
                    onClick = { onAnswer(rows, BatchAction.Skip) },
                    modifier = Modifier.semantics { contentDescription = "Skip all ${rows.size} ${label.lowercase()} on $dayName" },
                ) { Text("Skip all") }
            }
        }
    }
}

@Composable
private fun MissedRow(row: TimelineRow, dayName: String, onTake: () -> Unit, onSkip: () -> Unit) {
    val time = formatTime(row.scheduledTime)
    val take = if (row.kind == ReminderKind.Medication) "Take" else "Done"
    Column {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row {
                Text(time, Modifier.padding(end = 16.dp), fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f)) {
                    Text("${row.name} · ${kindLabel(row.kind)}", fontWeight = FontWeight.SemiBold)
                    row.dosage?.let { Text(it, color = TaskRxColors.Secondary) }
                }
            }
            // The day is in the description because the same name at the same time recurs on every day.
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onTake, modifier = Modifier.semantics { contentDescription = "$take ${row.name}, $dayName at $time" }) { Text(take) }
                OutlinedButton(onClick = onSkip, modifier = Modifier.semantics { contentDescription = "Skip ${row.name}, $dayName at $time" }) { Text("Skip") }
            }
        }
        HorizontalDivider(color = TaskRxColors.Divider)
    }
}
