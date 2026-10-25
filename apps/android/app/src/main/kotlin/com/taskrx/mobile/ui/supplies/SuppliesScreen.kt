package com.taskrx.mobile.ui.supplies

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.Reminder
import com.taskrx.core.refill.DEFAULT_REFILL_REMINDER_DAYS
import com.taskrx.core.refill.RefillProjection
import com.taskrx.mobile.format.RefillUrgency
import com.taskrx.mobile.format.refillDetail
import com.taskrx.mobile.format.refillHeadline
import com.taskrx.mobile.format.refillUrgency
import com.taskrx.mobile.refill.RefillRow
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.NumberField
import com.taskrx.mobile.ui.dose.RecordDialog

data class SuppliesActions(
    val onRefresh: () -> Unit,
    val onSort: (SupplySort) -> Unit,
    val onEdit: (String) -> Unit,
    val onCancelEdit: () -> Unit,
    val onSave: (String, SupplyDraft) -> Unit,
    val onOpenBacklog: () -> Unit,
    val onRecordDose: (Reminder, Int, Int) -> Unit,
)

/**
 * The Supplies screen, drawn from [state]. Mirrors the render half of
 * archive/expo-rn:apps/mobile/app/(app)/refill.tsx. A medication whose supply nobody recorded shows no
 * number: not a zero, not "0 days", which are the shape of an empty bottle. It gets a
 * sentence saying what is missing and the same box to put it right.
 */
@Composable
fun SuppliesContent(state: SuppliesState, actions: SuppliesActions, now: () -> Long, modifier: Modifier = Modifier) {
    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        skeletonRows = 5,
        loadingDescription = "Loading your supplies",
        modifier = modifier,
    ) {
        if (state.raised.isNotEmpty() || state.retired.isNotEmpty()) {
            item(key = "changes") { BacklogChanges(state.raised, state.retired, actions.onOpenBacklog) }
        }
        if (state.rows.isEmpty()) {
            item {
                // Said here rather than left to be inferred: a person looking for a task
                // they added would otherwise read this screen as broken.
                EmptyState(
                    "No medications yet",
                    "Supplies are a medication thing — a task has nothing to run out of.",
                    Modifier.fillParentMaxSize(),
                )
            }
        } else {
            item(key = "sort") { SortChips(state.sort, actions.onSort) }
            items(state.sortedRows, key = { it.medication.id }) { row ->
                MedicationRow(row, editing = state.editingId == row.medication.id, actions, now)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SortChips(selected: SupplySort, onSort: (SupplySort) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Sort by", color = TaskRxColors.Secondary, style = MaterialTheme.typography.labelLarge)
        for (sort in SupplySort.entries) {
            FilterChip(selected = sort == selected, onClick = { onSort(sort) }, label = { Text(sort.label) })
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider, modifier = Modifier.padding(top = 4.dp))
}

/** Both directions, not just the raise: a task disappearing on its own is the one a person would assume they had done. */
@Composable
private fun BacklogChanges(raised: List<String>, retired: List<String>, onOpenBacklog: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(TaskRxColors.NextTint).padding(16.dp)) {
        when (raised.size) {
            0 -> Unit
            1 -> Text("Added \"${raised[0]}\" to your backlog.", fontWeight = FontWeight.SemiBold)
            else -> Text("Added ${raised.size} reminders to your backlog.", fontWeight = FontWeight.SemiBold)
        }
        when (retired.size) {
            0 -> Unit
            1 -> Text("Cleared \"${retired[0]}\" — you have enough now.", fontWeight = FontWeight.SemiBold)
            else -> Text("Cleared ${retired.size} reminders you no longer need.", fontWeight = FontWeight.SemiBold)
        }
        TextButton(onClick = onOpenBacklog) { Text("Open the backlog") }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}

@Composable
private fun MedicationRow(row: RefillRow, editing: Boolean, actions: SuppliesActions, now: () -> Long) {
    val medication = row.medication
    val headline = refillHeadline(medication, row.projection)
    val detail = refillDetail(medication, row.projection)
    var recording by remember(medication.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        // One sentence for a screen reader: name, headline and working, not three fragments.
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${medication.name}: $headline, $detail" }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(medication.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                RefillBadge(refillUrgency(row.projection))
            }
            medication.dosage?.let { Text(it, Modifier.padding(top = 2.dp), color = TaskRxColors.Secondary) }
            Text(
                headline,
                Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (row.projection is RefillProjection.RefillOutlook) Color.Unspecified else TaskRxColors.Muted,
            )
            Text(detail, Modifier.padding(top = 2.dp), color = TaskRxColors.BodyText, style = MaterialTheme.typography.bodySmall)
        }
        if (editing) {
            SupplyEditor(row, actions)
        } else {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { recording = true }) { Text("Record a dose") }
                // Named for the act rather than the fields: someone opens this because they have just come back from the pharmacy.
                TextButton(onClick = { actions.onEdit(medication.id) }) { Text("I collected a refill") }
            }
        }
    }
    if (recording) {
        RecordDialog(medication, now(), onDismiss = { recording = false }, onRecord = { h, m -> recording = false; actions.onRecordDose(medication, h, m) })
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}

/** Said only when there is something to say: a standing "you're fine" on every row hides the two that are not. */
@Composable
private fun RefillBadge(urgency: RefillUrgency) {
    if (urgency == RefillUrgency.None) return
    val out = urgency == RefillUrgency.Out
    Text(
        if (out) "Out of stock" else "Reorder now",
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (out) TaskRxColors.ErrorTint else TaskRxColors.WarnTint)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        color = if (out) TaskRxColors.Error else TaskRxColors.Skipped,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SupplyEditor(row: RefillRow, actions: SuppliesActions) {
    val medication = row.medication
    var draft by remember(medication.id) {
        mutableStateOf(SupplyDraft(medication.pillsRemaining, medication.pillsPerDose, medication.refillReminderDays))
    }
    Column(Modifier.padding(top = 8.dp)) {
        NumberField("Pills remaining", medication.pillsRemaining, { draft = draft.copy(pillsRemaining = it) })
        NumberField("Pills per dose", medication.pillsPerDose, { draft = draft.copy(pillsPerDose = it) })
        NumberField(
            "Remind me to reorder this many days ahead",
            medication.refillReminderDays,
            // A whole number of days: the box reads a decimal, the column holds an integer.
            { draft = draft.copy(refillReminderDays = it?.toInt()?.coerceAtLeast(0)) },
        )
        // The default said out loud: a warning window has to be some number for a reminder
        // to ever be raised, so a blank one is silently seven days.
        HintText("Blank means $DEFAULT_REFILL_REMINDER_DAYS days.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions.onCancelEdit) { Text("Cancel") }
            TextButton(onClick = { actions.onSave(medication.id, draft) }) { Text("Save", fontWeight = FontWeight.Bold) }
        }
    }
}
