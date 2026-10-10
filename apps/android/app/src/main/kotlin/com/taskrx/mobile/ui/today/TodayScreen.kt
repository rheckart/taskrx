package com.taskrx.mobile.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.isAnswerable
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.timelineRowKey
import com.taskrx.mobile.format.dayHeading
import com.taskrx.mobile.format.emptyDayBody
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.format.kindLabel
import com.taskrx.mobile.format.rowDescription
import com.taskrx.mobile.format.statusLabel
import com.taskrx.mobile.ui.TaskRxColors

/** Where each footer link goes. Only the screens that exist are linked; later steps add the rest. */
data class TodayActions(
    val onPreviousDay: () -> Unit,
    val onNextDay: () -> Unit,
    val onToday: () -> Unit,
    val onRefresh: () -> Unit,
    val onOpenAnswer: (TimelineRow) -> Unit,
    val onAdd: () -> Unit,
    val onNewTask: () -> Unit,
    val onNewMedication: () -> Unit,
)

/**
 * The Today timeline, drawn from [state]. Mirrors the render half of
 * apps/mobile/app/(app)/index.tsx; the banner above it is the caller's, passed as [banner]
 * because its subject is whether any row can reach the person, not the day itself.
 */
@Composable
fun TodayContent(
    state: TodayState,
    actions: TodayActions,
    modifier: Modifier = Modifier,
    banner: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        banner()
        DayBar(state, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Timeline(state, actions)
        }
        Footer(actions)
    }
}

@Composable
private fun DayBar(state: TodayState, actions: TodayActions) {
    val heading = dayHeading(state.dayOffset, state.windowStart)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = actions.onPreviousDay, modifier = Modifier.semantics { contentDescription = "Previous day" }) {
            Text("‹", fontSize = 28.sp)
        }
        TextButton(onClick = actions.onToday, modifier = Modifier.semantics { contentDescription = "Back to today" }) {
            Text(heading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        // Forward only as far as today. On a future day every row is ahead of now, so the
        // first would be badged NEXT, which reads as "do this now" about something not due
        // for a day.
        TextButton(
            onClick = actions.onNextDay,
            enabled = state.dayOffset < 0,
            modifier = Modifier.semantics { contentDescription = "Next day" },
        ) {
            Text("›", fontSize = 28.sp)
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Timeline(state: TodayState, actions: TodayActions) {
    when {
        state.loading -> SkeletonList(rows = 5)
        state.error != null ->
            Centered {
                Text("Failed to load: ${state.error}", color = TaskRxColors.Error, textAlign = TextAlign.Center)
            }
        else ->
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                if (state.rows.isEmpty()) {
                    // Inside the refresh box so an empty day can still be pulled.
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Centered(Modifier.fillParentMaxSize()) {
                                Text("Nothing scheduled", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    emptyDayBody(state.dayOffset, dayHeading(state.dayOffset, state.windowStart)),
                                    Modifier.padding(top = 8.dp),
                                    color = TaskRxColors.Secondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.rows, key = { timelineRowKey(it.reminderId, it.scheduledTime) }) { row ->
                            TimelineRowView(row, onPress = { actions.onOpenAnswer(row) })
                        }
                    }
                }
            }
    }
}

@Composable
private fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** Placeholder rows while the day reads, so the screen has its shape before it has its content. */
@Composable
private fun SkeletonList(rows: Int) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).semantics { contentDescription = "Loading your day" },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(rows) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth(0.6f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(TaskRxColors.Divider))
                Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(TaskRxColors.Divider))
            }
        }
    }
}

/**
 * One row of the day.
 *
 * Three signals tell a dose from a task, because a single one always fails somebody: the
 * coloured edge carries a fast scan; the word beside the name carries a slow read and a
 * colour-blind one; and the same word is spoken in the row's description, the only channel
 * a screen reader has. The row is ONE target for a screen reader (Kind, name, time and
 * status as a phrase), not four.
 *
 * Whether it can be pressed is :core's `isAnswerable`, so the tap and the answer screen's
 * lookup ask the same question; an answered row is read but not offered as a button. A
 * snooze is a deferral, not an answer, so a snoozed moment stays open.
 */
@Composable
fun TimelineRowView(row: TimelineRow, onPress: () -> Unit, modifier: Modifier = Modifier) {
    val answered = !isAnswerable(row.status)
    val label = statusLabel(row)
    val kindColor = if (row.kind == ReminderKind.Medication) TaskRxColors.Medication else TaskRxColors.Task
    val description = rowDescription(row)

    val press =
        if (answered) {
            Modifier
        } else {
            Modifier.clickable(role = Role.Button, onClickLabel = "Answer", onClick = onPress)
        }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(press)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .background(if (row.isNext) TaskRxColors.NextTint else Color.Transparent),
        ) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(kindColor))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Column(Modifier.width(84.dp)) {
                    Text(
                        formatTime(row.scheduledTime),
                        fontWeight = FontWeight.SemiBold,
                        color = if (answered) TaskRxColors.Muted else Color.Unspecified,
                    )
                    if (row.isNext) {
                        Text("NEXT", color = TaskRxColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            row.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (answered) TaskRxColors.Muted else Color.Unspecified,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            kindLabel(row.kind),
                            Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (row.kind == ReminderKind.Medication) TaskRxColors.MedicationTint else TaskRxColors.TaskTint)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                            color = kindColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    row.dosage?.let { Text(it, Modifier.padding(top = 2.dp), color = TaskRxColors.Secondary, fontSize = 14.sp) }
                    if (label.isNotEmpty()) {
                        Text(label, Modifier.padding(top = 4.dp), color = statusColor(row.status), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        HorizontalDivider(color = TaskRxColors.Divider)
    }
}

private fun statusColor(status: InstanceStatus): Color =
    when (status) {
        InstanceStatus.Completed -> TaskRxColors.Completed
        InstanceStatus.Missed -> TaskRxColors.Error
        InstanceStatus.Pending -> TaskRxColors.Secondary
        InstanceStatus.Skipped -> TaskRxColors.Skipped
        InstanceStatus.Snoozed -> TaskRxColors.Snoozed
    }

/**
 * "+ Add" is first, filled, and the only one that says "+" for both Kinds: natural-language
 * entry is the primary creation surface for both. The two hand-entry forms stay reachable,
 * because "+ Task" is still the shorter road when there was never a phrase to type.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(actions: TodayActions) {
    HorizontalDivider(color = TaskRxColors.Divider)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = actions.onAdd, shape = RoundedCornerShape(6.dp)) { Text("+ Add", fontWeight = FontWeight.Bold) }
        TextButton(onClick = actions.onNewTask) { Text("+ Task") }
        TextButton(onClick = actions.onNewMedication) { Text("+ Medication") }
    }
}
