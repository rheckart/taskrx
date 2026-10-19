package com.taskrx.mobile.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import com.taskrx.mobile.ui.forms.TimeDialog
import java.time.Instant
import java.time.ZoneId
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
import androidx.compose.material3.FilterChip
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
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.batch.batchSlots
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
import com.taskrx.mobile.ui.components.Centered
import com.taskrx.mobile.ui.components.SkeletonList

/** Where each footer link goes. */
data class TodayActions(
    val onPreviousDay: () -> Unit,
    val onNextDay: () -> Unit,
    val onToday: () -> Unit,
    val onToggleHideCompleted: () -> Unit,
    /** Take, snooze or skip every dose in a slot: the rows share one moment. */
    val onAnswerSlot: (List<TimelineRow>, BatchAction) -> Unit,
    val onRefresh: () -> Unit,
    val onOpenAnswer: (TimelineRow) -> Unit,
    /** Move the time a completed dose was taken to this hour and minute, on its own day. */
    val onChangeTime: (TimelineRow, Int, Int) -> Unit,
    val onAdd: () -> Unit,
    val onNewTask: () -> Unit,
    val onNewMedication: () -> Unit,
    val onAddDose: () -> Unit,
    val onReminders: () -> Unit,
    val onBacklog: () -> Unit,
    val onAdherence: () -> Unit,
    val onSupplies: () -> Unit,
    val onHistory: () -> Unit,
)

/**
 * The Today timeline, drawn from [state]. Mirrors the render half of
 * archive/expo-rn:apps/mobile/app/(app)/index.tsx; the banner above it is the caller's, passed as [banner]
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
            Text("‹", fontSize = 44.sp)
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
            Text("›", fontSize = 44.sp)
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        FilterChip(
            selected = state.hideCompleted,
            onClick = actions.onToggleHideCompleted,
            label = { Text("Hide done") },
        )
    }
    HorizontalDivider(color = TaskRxColors.Divider, modifier = Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Timeline(state: TodayState, actions: TodayActions) {
    // The dose whose time is being changed, if any.
    var editing by remember { mutableStateOf<TimelineRow?>(null) }
    editing?.let { row ->
        val at = (row.completedAt ?: row.scheduledTime).let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        TimeDialog(
            hour = at.hour,
            minute = at.minute,
            onDismiss = { editing = null },
            onConfirm = { hour, minute ->
                editing = null
                actions.onChangeTime(row, hour, minute)
            },
        )
    }
    when {
        state.loading -> SkeletonList(rows = 5, description = "Loading your day")
        state.error != null ->
            Centered {
                Text("Failed to load: ${state.error}", color = TaskRxColors.Error, textAlign = TextAlign.Center)
            }
        else ->
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                val visible = state.visibleRows
                if (visible.isEmpty()) {
                    // Inside the refresh box so an empty day can still be pulled.
                    val allDone = state.rows.isNotEmpty()
                    LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Centered(Modifier.fillParentMaxSize()) {
                                Text(
                                    if (allDone) "All done" else "Nothing scheduled",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    if (allDone) "Everything on this day is completed." else emptyDayBody(state.dayOffset, dayHeading(state.dayOffset, state.windowStart)),
                                    Modifier.padding(top = 8.dp),
                                    color = TaskRxColors.Secondary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                } else {
                    // A slot's buttons sit above its first row, as a list entry of their own.
                    val slots = batchSlots(visible)
                    val entries = mutableListOf<Entry>()
                    for (row in visible) {
                        val slot = slots[row.scheduledTime]
                        if (slot != null && entries.none { it is Entry.Slot && it.scheduledTime == row.scheduledTime }) {
                            entries += Entry.Slot(row.scheduledTime, slot)
                        }
                        entries += Entry.Dose(row)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(entries, key = { it.key }) { entry ->
                            when (entry) {
                                is Entry.Slot -> SlotBar(entry, onAnswer = { actions.onAnswerSlot(entry.rows, it) })
                                is Entry.Dose ->
                                    TimelineRowView(
                                        entry.row,
                                        onPress = { actions.onOpenAnswer(entry.row) },
                                        onLongPress = if (entry.row.status == InstanceStatus.Completed) ({ editing = entry.row }) else null,
                                    )
                            }
                        }
                    }
                }
            }
    }
}

private sealed interface Entry {
    val key: String

    class Slot(val scheduledTime: Long, val rows: List<TimelineRow>) : Entry {
        override val key = "slot-$scheduledTime"
    }

    class Dose(val row: TimelineRow) : Entry {
        override val key = timelineRowKey(row.reminderId, row.scheduledTime)
    }
}

/** Three buttons that answer every dose due at one moment. */
@Composable
private fun SlotBar(slot: Entry.Slot, onAnswer: (BatchAction) -> Unit) {
    val time = formatTime(slot.scheduledTime)
    Column(Modifier.fillMaxWidth().background(TaskRxColors.NextTint).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text("$time · ${slot.rows.size} doses", style = MaterialTheme.typography.labelLarge, color = TaskRxColors.Secondary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((action, label) in listOf(BatchAction.Take to "Take all", BatchAction.Snooze to "Snooze all", BatchAction.Skip to "Skip all")) {
                TextButton(
                    onClick = { onAnswer(action) },
                    modifier = Modifier.semantics { contentDescription = "$label ${slot.rows.size} doses at $time" },
                ) { Text(label, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
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
fun TimelineRowView(row: TimelineRow, onPress: () -> Unit, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
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
    // Held, not tapped, and not a click: an answered row still offers nothing to press, but
    // a held one can have the time it was taken changed. The same action is on the row's
    // semantics so a screen reader can reach it.
    val hold =
        if (onLongPress == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(row.instanceId) { detectTapGestures(onLongPress = { onLongPress() }) }
                .semantics { onLongClick(label = "Change the time taken") { onLongPress(); true } }
        }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .then(press)
                .then(hold)
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

@Composable
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
        TextButton(onClick = actions.onAddDose) { Text("+ Dose") }
        TextButton(onClick = actions.onReminders) { Text("Reminders") }
        TextButton(onClick = actions.onBacklog) { Text("Backlog") }
        TextButton(onClick = actions.onAdherence) { Text("How it's going") }
        TextButton(onClick = actions.onSupplies) { Text("Supplies") }
        TextButton(onClick = actions.onHistory) { Text("History") }
    }
}
