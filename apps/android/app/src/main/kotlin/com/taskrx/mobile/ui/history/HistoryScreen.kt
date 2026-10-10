package com.taskrx.mobile.ui.history

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.mobile.format.completedAtLabel
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.format.groupByDay
import com.taskrx.mobile.format.historyStatusLabel
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.SectionHeader

/** The history list, drawn from [state]. Mirrors the render half of apps/mobile/app/(app)/dose/history.tsx. */
@Composable
fun HistoryContent(state: HistoryState, onRefresh: () -> Unit, now: Long, modifier: Modifier = Modifier) {
    val groups = remember(state.items, now) { groupByDay(state.items, now) { it.instance.scheduledTime } }

    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        skeletonRows = 8,
        loadingDescription = "Loading your history",
        modifier = modifier,
    ) {
        if (groups.isEmpty()) {
            item {
                EmptyState(
                    "Nothing recorded yet",
                    "Reminders you mark taken, done or skipped will appear here.",
                    Modifier.fillParentMaxSize(),
                )
            }
        } else {
            for (group in groups) {
                item(key = "day-${group.title}") { SectionHeader(group.title) }
                items(group.items, key = { it.instance.id }) { HistoryRow(it) }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    val instance = item.instance
    val status = historyStatusLabel(instance.status, instance.reminderKind)
    val scheduled = formatTime(instance.scheduledTime)
    val completed = instance.completedAt?.let { "${completedAtLabel(instance.reminderKind)}: ${formatTime(it)}" }

    Column(
        Modifier
            .fillMaxWidth()
            // One phrase for a screen reader, not five fragments.
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.name}, $status, scheduled $scheduled${completed?.let { ", $it" } ?: ""}"
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(status, color = statusColor(instance.status), fontWeight = FontWeight.SemiBold)
        }
        item.dosage?.let { Text(it, Modifier.padding(top = 2.dp), color = TaskRxColors.Secondary) }
        Text("Scheduled: $scheduled", Modifier.padding(top = 4.dp), color = Color(0xFF444444))
        completed?.let { Text(it, Modifier.padding(top = 4.dp), color = Color(0xFF444444)) }
        instance.notes?.takeIf { it.isNotEmpty() }?.let {
            Text(it, Modifier.padding(top = 4.dp), color = TaskRxColors.Secondary, fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodySmall)
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}

private fun statusColor(status: InstanceStatus): Color =
    when (status) {
        InstanceStatus.Completed -> TaskRxColors.CompleteButton
        InstanceStatus.Snoozed -> Color(0xFF8E8E93)
        InstanceStatus.Skipped -> TaskRxColors.SkipButton
        InstanceStatus.Missed -> TaskRxColors.Error
        InstanceStatus.Pending -> TaskRxColors.Accent
    }
