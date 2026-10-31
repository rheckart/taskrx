package com.taskrx.mobile.ui.catchup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.mobile.format.formatDay
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.format.kindLabel
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.SectionHeader

/** What the whole-list buttons are about to do, waiting on "Are you sure?". */
private data class Pending(val action: BatchAction, val count: Int)

/**
 * Every Missed dose and task of the last week, by day. A day's buttons answer that day; the
 * pair at the top answers the lot, after a confirmation. Leaving the screen leaves them Missed.
 */
@Composable
fun CatchUpContent(
    state: CatchUpState,
    onRefresh: () -> Unit,
    onAnswer: (List<TimelineRow>, BatchAction) -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember { mutableStateOf<Pending?>(null) }
    val all = state.days.flatMap { it.rows }

    pending?.let { ask ->
        val words = if (ask.action == BatchAction.Take) "Mark all ${ask.count} as done?" else "Skip all ${ask.count}?"
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Are you sure?") },
            text = { Text(words) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    onAnswer(all, ask.action)
                }) { Text("Yes") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize()) {
        state.undo?.let { undo ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                            "Marking done uses the time each one was due.",
                            Modifier.padding(top = 4.dp),
                            color = TaskRxColors.Secondary,
                        )
                        Answers(
                            what = "everything",
                            onDone = { pending = Pending(BatchAction.Take, all.size) },
                            onSkip = { pending = Pending(BatchAction.Skip, all.size) },
                        )
                    }
                    HorizontalDivider(color = TaskRxColors.Divider)
                }
                for (day in state.days) {
                    item(key = "day-${day.dayStart}") {
                        Column {
                            SectionHeader(formatDay(day.dayStart))
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                Answers(
                                    what = formatDay(day.dayStart),
                                    onDone = { onAnswer(day.rows, BatchAction.Take) },
                                    onSkip = { onAnswer(day.rows, BatchAction.Skip) },
                                )
                            }
                        }
                    }
                    items(day.rows, key = { "${it.reminderId}-${it.scheduledTime}" }) { MissedRow(it) }
                }
            }
        }
    }
}

@Composable
private fun Answers(what: String, onDone: () -> Unit, onSkip: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onDone, modifier = Modifier.semantics { contentDescription = "Mark $what done" }) { Text("Done") }
        OutlinedButton(onClick = onSkip, modifier = Modifier.semantics { contentDescription = "Skip $what" }) { Text("Skip") }
    }
}

@Composable
private fun MissedRow(row: TimelineRow) {
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(formatTime(row.scheduledTime), Modifier.padding(end = 16.dp), fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f)) {
                Text("${row.name} · ${kindLabel(row.kind)}", fontWeight = FontWeight.SemiBold)
                row.dosage?.let { Text(it, color = TaskRxColors.Secondary) }
            }
        }
        HorizontalDivider(color = TaskRxColors.Divider)
    }
}
