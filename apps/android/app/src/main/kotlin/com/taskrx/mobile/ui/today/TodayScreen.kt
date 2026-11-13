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
import kotlinx.coroutines.delay
import java.time.ZoneId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskrx.core.asneeded.asNeededStatus
import com.taskrx.core.asneeded.showsOnToday
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.batch.batchSlots
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.isAnswerable
import com.taskrx.core.tasks.UpcomingDay
import com.taskrx.core.time.localCalendarDaysBetween
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.timelineRowKey
import com.taskrx.core.undo.undoWillBeMissed
import com.taskrx.mobile.asneeded.AsNeededEntry
import com.taskrx.mobile.format.asNeededLines
import com.taskrx.mobile.format.dayHeading
import com.taskrx.mobile.format.emptyDayBody
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.format.kindLabel
import com.taskrx.mobile.format.rowDescription
import com.taskrx.mobile.format.statusLabel
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.Centered
import com.taskrx.mobile.ui.components.KindTabs
import com.taskrx.mobile.ui.components.SectionHeader
import com.taskrx.mobile.ui.components.SkeletonList
import com.taskrx.mobile.ui.components.tabWord

/** Where each footer link goes. */
data class TodayActions(
    val onPreviousDay: () -> Unit,
    val onNextDay: () -> Unit,
    val onToday: () -> Unit,
    val onToggleHideCompleted: () -> Unit,
    val onSelectKind: (ReminderKind) -> Unit = {},
    /**
     * Take, snooze or skip every dose in a slot: the rows share one moment. A Take of missed
     * doses carries the hour and minute the person said they were taken at; otherwise both are null.
     */
    val onAnswerSlot: (List<TimelineRow>, BatchAction, Int?, Int?) -> Unit,
    val onRefresh: () -> Unit,
    val onOpenAnswer: (TimelineRow) -> Unit,
    /** Move the time a completed dose was taken to this hour and minute, on its own day. */
    val onChangeTime: (TimelineRow, Int, Int) -> Unit,
    val onUndo: (TimelineRow) -> Unit,
    val onAdd: () -> Unit,
    val onNewTask: () -> Unit,
    val onNewMedication: () -> Unit,
    val onAddDose: () -> Unit,
    val onReminders: () -> Unit,
    val onBacklog: () -> Unit,
    val onAdherence: () -> Unit,
    val onSupplies: () -> Unit,
    val onHistory: () -> Unit,
    val onSettings: () -> Unit,
    val onCatchUp: () -> Unit = {},
    /** Answer a task from its Overdue row: Done or Skip, without the answer screen. */
    val onAnswerTask: (TimelineRow, BatchAction) -> Unit = { _, _ -> },
    /** Record a dose of an as-needed medication as taken now. */
    val onTakeAsNeeded: (Reminder) -> Unit = {},
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
    /** Read when a button is pressed, so a screen left open for hours still judges by the real time. */
    now: () -> Long = System::currentTimeMillis,
) {
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            banner()
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindTabs(state.kind, state.toDoByKind, actions.onSelectKind, Modifier.weight(1f))
                MoreMenu(actions)
            }
            DayBar(state, actions)
            // Outside the list, so an as-needed medication on its own does not read as "no medications scheduled".
            if (state.kind == ReminderKind.Medication && state.dayOffset == 0 && state.asNeeded.isNotEmpty()) {
                AsNeededCards(state.asNeeded, actions.onTakeAsNeeded, now)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Timeline(state, actions, now)
            }
        }
        AddButton(actions, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

/**
 * The medications taken as needed whose window is open: a dose is allowed now, and became so
 * within the last hour. A medication waiting for its next dose stays off the screen, so a long
 * gap is not a countdown taking up the day. Read from the takings every minute rather than
 * stored, so a card appears when its window opens and goes when it closes.
 */
@Composable
private fun AsNeededCards(entries: List<AsNeededEntry>, onTake: (Reminder) -> Unit, now: () -> Long) {
    var current by remember(entries) { mutableLongStateOf(now()) }
    LaunchedEffect(entries) {
        while (true) {
            current = now()
            delay(60_000L - current % 60_000L)
        }
    }
    val showing = entries.filter { shows(it, current) }
    if (showing.isEmpty()) return
    Text(
        "As needed",
        Modifier.padding(start = 16.dp, top = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = TaskRxColors.Secondary,
    )
    for (entry in showing) {
        val status = asNeededStatus(entry.schedule, entry.takings, current)
        val lines = asNeededLines(entry.schedule, status, current)
        val name = entry.reminder.name + (entry.reminder.dosage?.let { " ($it)" } ?: "")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .semantics(mergeDescendants = true) { contentDescription = "$name. ${lines.headline}. ${lines.detail}" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(lines.headline, fontWeight = FontWeight.SemiBold, color = TaskRxColors.Completed)
                if (lines.detail.isNotEmpty()) Text(lines.detail, color = TaskRxColors.Secondary, fontSize = 14.sp)
            }
            Button(
                onClick = { onTake(entry.reminder) },
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.semantics { contentDescription = "Take ${entry.reminder.name} now" },
            ) { Text("Take") }
        }
        HorizontalDivider(color = TaskRxColors.Divider)
    }
}

/** A card is shown only while its window is open (`showsOnToday`). */
private fun shows(entry: AsNeededEntry, now: Long): Boolean = showsOnToday(asNeededStatus(entry.schedule, entry.takings, now), now)

@Composable
private fun DayBar(state: TodayState, actions: TodayActions) {
    // The Tasks tab has no day: Overdue is the days behind and Upcoming is the week
    // ahead. Only Medications page by day, and only they are headed by one.
    if (state.kind == ReminderKind.Medication) DayNav(state, actions)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        FilterChip(
            selected = state.hideCompleted,
            onClick = actions.onToggleHideCompleted,
            // Says what a tap does: while done rows are hidden it offers to show them.
            label = { Text(if (state.hideCompleted) "Show done" else "Hide done") },
        )
    }
    HorizontalDivider(color = TaskRxColors.Divider, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun DayNav(state: TodayState, actions: TodayActions) {
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
        // Medications stop at today; tasks can look ahead.
        TextButton(
            onClick = actions.onNextDay,
            enabled = state.canGoForward,
            modifier = Modifier.semantics { contentDescription = "Next day" },
        ) {
            Text("›", fontSize = 44.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Timeline(state: TodayState, actions: TodayActions, now: () -> Long) {
    // The dose whose time is being changed, if any.
    var editing by remember { mutableStateOf<TimelineRow?>(null) }
    // The slot whose overdue doses are waiting to be told when they were taken.
    var takingAll by remember { mutableStateOf<List<TimelineRow>?>(null) }
    takingAll?.let { rows ->
        val opened = Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault())
        TimeDialog(
            hour = opened.hour,
            minute = opened.minute,
            title = "When did you take them?",
            onDismiss = { takingAll = null },
            onConfirm = { hour, minute ->
                takingAll = null
                actions.onAnswerSlot(rows, BatchAction.Take, hour, minute)
            },
        )
    }
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
        state.kind == ReminderKind.Task ->
            TaskTimeline(
                state = state,
                actions = actions,
                now = now,
                onEdit = { editing = it },
            )
        else ->
            MedicationTimeline(
                state = state,
                actions = actions,
                now = now,
                onTakeAll = { takingAll = it },
                onEdit = { editing = it },
            )
    }
}

/** The Medications tab: one day, slot bars, and the day's empty states. Unchanged by the Tasks sections. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MedicationTimeline(
    state: TodayState,
    actions: TodayActions,
    now: () -> Long,
    onTakeAll: (List<TimelineRow>) -> Unit,
    onEdit: (TimelineRow) -> Unit,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        val visible = state.visibleRows
        if (visible.isEmpty()) {
            // Inside the refresh box so an empty day can still be pulled.
            val allDone = state.tabRows.isNotEmpty()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Centered(Modifier.fillParentMaxSize()) {
                        // With as-needed medications above, "no medications" would sit under a card that says
                        // "Ready now": it is the scheduled doses there are none of.
                        val asNeededAbove = state.kind == ReminderKind.Medication && state.dayOffset == 0 && state.asNeeded.any { shows(it, now()) }
                        Text(
                            when {
                                allDone -> "All done"
                                asNeededAbove -> "No scheduled doses"
                                else -> "No ${tabWord(state.kind).lowercase()} scheduled"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                allDone -> "Everything on this day is completed."
                                asNeededAbove -> "Take an as-needed medication from its card above."
                                else -> emptyDayBody(state.dayOffset, dayHeading(state.dayOffset, state.windowStart))
                            },
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
            // Room under the last row for the + button to float over.
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is Entry.Slot ->
                            SlotBar(
                                entry,
                                onAnswer = {
                                    // Past its Grace Period the app can only guess; ask rather than record now.
                                    if (it == BatchAction.Take && entry.rows.any { row -> isPastGrace(row, now()) }) {
                                        onTakeAll(entry.rows)
                                    } else {
                                        actions.onAnswerSlot(entry.rows, it, null, null)
                                    }
                                },
                            )
                        is Entry.Dose ->
                            TimelineRowView(
                                shownStatus(entry.row, now()),
                                onPress = { actions.onOpenAnswer(entry.row) },
                                onLongPress = if (entry.row.status == InstanceStatus.Completed) ({ onEdit(entry.row) }) else null,
                                onUndo = if (entry.row.instanceId in state.undoable) ({ actions.onUndo(entry.row) }) else null,
                            )
                    }
                }
            }
        }
    }
}

/**
 * The Tasks tab: Overdue, Due today and Upcoming, each always headed. Each is filtered by
 * Hide done on its own, and one that is empty after filtering says so rather than vanishing.
 * When Hide done empties every section, "All done" says the person finished rather than that
 * nothing was ever there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskTimeline(
    state: TodayState,
    actions: TodayActions,
    now: () -> Long,
    onEdit: (TimelineRow) -> Unit,
) {
    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
        val sections = state.sections
        val overdue = sections.overdue
        val dueToday = shownTasks(sections.dueToday, state.hideCompleted)
        val upcoming = shownUpcoming(sections.upcoming, state.hideCompleted)
        val anyRow = overdue.isNotEmpty() || sections.dueToday.isNotEmpty() || sections.upcoming.any { it.rows.isNotEmpty() }
        val anyVisible = overdue.isNotEmpty() || dueToday.isNotEmpty() || upcoming.isNotEmpty()

        if (!anyVisible && anyRow) {
            // Everything the tab has is done or skipped, and Hide done is on.
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Centered(Modifier.fillParentMaxSize()) {
                        Text("All done", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Everything on this list is completed.",
                            Modifier.padding(top = 8.dp),
                            color = TaskRxColors.Secondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            return@PullToRefreshBox
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
            item(key = "header-overdue") { SectionHeader("Overdue") }
            if (overdue.isEmpty()) {
                item(key = "empty-overdue") { EmptySection("Nothing overdue") }
            } else {
                items(overdue, key = { "overdue-${it.reminderId}-${it.scheduledTime}" }) { row ->
                    OverdueTaskRow(
                        row = row,
                        canUndo = row.instanceId in state.undoable,
                        onPress = { actions.onOpenAnswer(row) },
                        onDone = { actions.onAnswerTask(row, BatchAction.Take) },
                        onSkip = { actions.onAnswerTask(row, BatchAction.Skip) },
                        onUndo = { actions.onUndo(row) },
                    )
                }
            }

            item(key = "header-today") { SectionHeader("Due today") }
            if (dueToday.isEmpty()) {
                item(key = "empty-today") { EmptySection("Nothing due today") }
            } else {
                items(dueToday, key = { "today-${it.reminderId}-${it.scheduledTime}" }) { row ->
                    TaskRow(row, state, actions, onEdit)
                }
            }

            item(key = "header-upcoming") { SectionHeader("Upcoming") }
            if (upcoming.isEmpty()) {
                item(key = "empty-upcoming") { EmptySection("Nothing upcoming") }
            } else {
                for (day in upcoming) {
                    item(key = "upcoming-day-${day.dayStart}") { UpcomingDayHeader(day, now()) }
                    items(day.rows, key = { "upcoming-${it.reminderId}-${it.scheduledTime}" }) { row ->
                        TaskRow(row, state, actions, onEdit)
                    }
                }
            }
        }
    }
}

private fun shownTasks(rows: List<TimelineRow>, hideCompleted: Boolean): List<TimelineRow> =
    if (hideCompleted) rows.filter { it.status != InstanceStatus.Completed && it.status != InstanceStatus.Skipped } else rows

private fun shownUpcoming(days: List<UpcomingDay>, hideCompleted: Boolean): List<UpcomingDay> =
    days
        .map { UpcomingDay(it.dayStart, shownTasks(it.rows, hideCompleted)) }
        .filter { it.rows.isNotEmpty() }

@Composable
private fun EmptySection(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        color = TaskRxColors.Secondary,
    )
}

@Composable
private fun UpcomingDayHeader(day: UpcomingDay, now: Long) {
    val offset = localCalendarDaysBetween(now, day.dayStart)
    Text(
        dayHeading(offset, day.dayStart),
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = TaskRxColors.Secondary,
    )
}

/** One task in Due today or Upcoming: the row is the way to the answer screen. */
@Composable
private fun TaskRow(row: TimelineRow, state: TodayState, actions: TodayActions, onEdit: (TimelineRow) -> Unit) {
    TimelineRowView(
        row,
        onPress = { actions.onOpenAnswer(row) },
        onLongPress = if (row.status == InstanceStatus.Completed) ({ onEdit(row) }) else null,
        onUndo = if (row.instanceId in state.undoable) ({ actions.onUndo(row) }) else null,
    )
}

/**
 * An Overdue task. The word is the screen's, not a stored status: the row is still `pending` or
 * `missed` underneath, and Done or Skip answers it in place without a trip to the answer screen.
 */
@Composable
private fun OverdueTaskRow(
    row: TimelineRow,
    canUndo: Boolean,
    onPress: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
    onUndo: () -> Unit,
) {
    TimelineRowView(
        row = row,
        onPress = onPress,
        statusText = "Overdue",
        onUndo = if (canUndo) onUndo else null,
        actions = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.semantics { contentDescription = "Done ${row.name}" },
                ) { Text("Done") }
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.semantics { contentDescription = "Skip ${row.name}" },
                ) { Text("Skip") }
            }
        },
    )
}

/**
 * Whether a dose is Missed, or will be as soon as the sweep next runs. The stored status
 * lags the clock (the sweep only runs when the app opens), so the time is checked too.
 */
private fun isPastGrace(row: TimelineRow, now: Long): Boolean =
    row.status == InstanceStatus.Missed || (isAnswerable(row.status) && undoWillBeMissed(row.scheduledTime, now))

/**
 * The row as it reads on screen. An unanswered dose past its Grace Period is shown Missed even
 * before the sweep has stored it, so one dose never says Missed while its neighbours at the same
 * moment say nothing. Only Pending: a snooze may run later than the dose's own moment.
 */
private fun shownStatus(row: TimelineRow, now: Long): TimelineRow =
    if (row.status == InstanceStatus.Pending && undoWillBeMissed(row.scheduledTime, now)) row.copy(status = InstanceStatus.Missed) else row

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
fun TimelineRowView(
    row: TimelineRow,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    onUndo: (() -> Unit)? = null,
    /** Overrides the status word, for a display-only word such as an Overdue task's. */
    statusText: String? = null,
    /** Extra controls under the row, outside its merged description. */
    actions: (@Composable () -> Unit)? = null,
) {
    val answered = !isAnswerable(row.status)
    val label = statusText ?: statusLabel(row)
    val kindColor = if (row.kind == ReminderKind.Medication) TaskRxColors.Medication else TaskRxColors.Task
    val description = rowDescription(row, statusText = statusText)

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
        // Outside the row's merged description: it is a control of its own, not part of what the row says.
        if (onUndo != null) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onUndo) { Text("Undo ${undoLabel(row.status, row.kind)}") }
            }
        }
        actions?.invoke()
        HorizontalDivider(color = TaskRxColors.Divider)
    }
}

/** What Undo takes back, named on the button so the row reads "Undo skip", not an unexplained "Undo". */
private fun undoLabel(status: InstanceStatus, kind: ReminderKind): String =
    when (status) {
        InstanceStatus.Completed -> if (kind == ReminderKind.Medication) "take" else "done"
        InstanceStatus.Skipped -> "skip"
        else -> "snooze"
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
 * The way to make something new, one round button over the list. "Add" is first and is the
 * only one that takes a phrase: natural-language entry is the primary creation surface for
 * both Kinds. The hand-entry forms stay reachable, because "New task" is still the shorter
 * road when there was never a phrase to type.
 */
@Composable
private fun AddButton(actions: TodayActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FloatingActionButton(
            onClick = { open = true },
            modifier = Modifier.semantics { contentDescription = "Add" },
        ) { Text("+", fontSize = 28.sp, fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, go) in
                listOf(
                    "Add by typing" to actions.onAdd,
                    "New task" to actions.onNewTask,
                    "New medication" to actions.onNewMedication,
                    "Log a dose" to actions.onAddDose,
                )) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; go() })
            }
        }
    }
}

/** The screens that are not today: the rest of what the footer used to hold. */
@Composable
private fun MoreMenu(actions: TodayActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "More" }) {
            Text("⋮", fontSize = 28.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, go) in
                listOf(
                    "Reminders" to actions.onReminders,
                    "Backlog" to actions.onBacklog,
                    "Catch up" to actions.onCatchUp,
                    "How it's going" to actions.onAdherence,
                    "Supplies" to actions.onSupplies,
                    "History" to actions.onHistory,
                    "Settings" to actions.onSettings,
                )) {
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; go() })
            }
        }
    }
}
