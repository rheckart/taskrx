package com.taskrx.mobile.ui.notices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.notices.Proposal
import com.taskrx.core.notices.ProposalRefusal
import com.taskrx.core.notices.ReadDate
import com.taskrx.mobile.format.formatDay
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.forms.InstantField
import java.time.ZoneId

/**
 * Notices a watched source admitted. Each shows where it came from, its subject, the sentence
 * that was kept and the date the reader found in it, then what the person can do: confirm a
 * move a rule proposed, apply the notice to a task by hand, make a one-off task, or put it
 * aside. Nothing moves on its own.
 */
@Composable
fun NoticesContent(
    state: NoticesState,
    onRefresh: () -> Unit,
    onBeginTask: (Notice) -> Unit,
    onDismiss: (Notice) -> Unit,
    onDraftAt: (Long) -> Unit,
    onSaveTask: () -> Unit,
    onCancelTask: () -> Unit,
    onApply: (Notice, Proposal) -> Unit = { _, _ -> },
    onBeginPick: (Notice) -> Unit = {},
    onPickTask: (Reminder) -> Unit = {},
    onCancelPick: () -> Unit = {},
    onBeginOccurrence: (Notice, Proposal) -> Unit = { _, _ -> },
    onChooseOccurrence: (Long) -> Unit = {},
    onCancelOccurrence: () -> Unit = {},
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val draft = state.draft
    if (draft != null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            FieldLabel("From this notice", Modifier.padding(top = 0.dp))
            Text(draft.notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            draft.notice.sentence?.let { Text(it, Modifier.padding(top = 4.dp), color = TaskRxColors.Secondary) }
            InstantField(
                label = "When",
                hint = "The date read from the notice, or one you pick.",
                value = draft.at,
                onChange = onDraftAt,
                now = now,
            )
            PrimaryButton("Add task", onSaveTask, Modifier.padding(top = 24.dp), busy = state.saving)
            TextButton(onClick = onCancelTask, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
        }
        return
    }

    val pick = state.pick
    if (pick != null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            FieldLabel("Apply this notice to", Modifier.padding(top = 0.dp))
            Text(pick.notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (pick.tasks.isEmpty()) {
                HintText("No tasks yet. Make one from this notice instead.")
            }
            for (task in pick.tasks) {
                OutlinedButton(
                    onClick = { onPickTask(task) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(task.name) }
            }
            TextButton(onClick = onCancelPick, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
        }
        return
    }

    val occurrence = state.occurrencePick
    if (occurrence != null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            FieldLabel("Move which occurrence?", Modifier.padding(top = 0.dp))
            Text(occurrence.proposal.reminder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            HintText("The notice names ${formatFullDayTime(occurrence.proposal.to, zone)}.")
            for (moment in occurrence.options) {
                OutlinedButton(
                    onClick = { onChooseOccurrence(moment) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) { Text(formatFullDayTime(moment, zone)) }
            }
            TextButton(onClick = onCancelOccurrence, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
        }
        return
    }

    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        skeletonRows = 4,
        loadingDescription = "Loading notices",
        modifier = modifier,
    ) {
        if (state.notices.isEmpty()) {
            item { EmptyState("No notices", "Messages from your watched senders and apps appear here.", Modifier.fillParentMaxSize()) }
        } else {
            item(key = "count") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        if (state.unresolved == 1) "1 waiting for you" else "${state.unresolved} waiting for you",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    HintText("Nothing here changes a task until you say so.")
                }
                HorizontalDivider(color = TaskRxColors.Divider)
            }
            items(state.notices, key = { it.id }) { notice ->
                NoticeRow(
                    notice = notice,
                    reading = state.readings[notice.id],
                    proposals = state.proposals[notice.id].orEmpty(),
                    zone = zone,
                    onBeginTask = onBeginTask,
                    onDismiss = onDismiss,
                    onApply = onApply,
                    onBeginPick = onBeginPick,
                    onBeginOccurrence = onBeginOccurrence,
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(
    notice: Notice,
    reading: ReadDate?,
    proposals: List<Proposal>,
    zone: ZoneId,
    onBeginTask: (Notice) -> Unit,
    onDismiss: (Notice) -> Unit,
    onApply: (Notice, Proposal) -> Unit,
    onBeginPick: (Notice) -> Unit,
    onBeginOccurrence: (Notice, Proposal) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(sourceLabel(notice.sourceKind, notice.sourceValue), style = MaterialTheme.typography.labelMedium, color = TaskRxColors.Secondary)
        Text(notice.title, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        notice.sentence?.let {
            Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        }
        reading?.let {
            Text(readingLabel(it, zone), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)
        }
        Text("Received ${formatFullDayTime(notice.receivedAt, zone)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)

        for (proposal in proposals) {
            ProposalCard(notice, proposal, zone, onApply, onBeginOccurrence)
        }

        when (notice.resolution) {
            NoticeResolution.None ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onBeginTask(notice) },
                        modifier = Modifier.semantics { contentDescription = "New task from ${notice.title}" },
                    ) { Text("New task") }
                    OutlinedButton(
                        onClick = { onBeginPick(notice) },
                        modifier = Modifier.semantics { contentDescription = "Apply ${notice.title} to a task" },
                    ) { Text("Apply to a task") }
                    OutlinedButton(
                        onClick = { onDismiss(notice) },
                        modifier = Modifier.semantics { contentDescription = "Dismiss ${notice.title}" },
                    ) { Text("Dismiss") }
                }
            NoticeResolution.Applied -> StatusLine("Moved a task")
            NoticeResolution.NewTask -> StatusLine("Turned into a task")
            NoticeResolution.Dismissed -> StatusLine("Dismissed")
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = TaskRxColors.Divider)
    }
}

@Composable
private fun ProposalCard(
    notice: Notice,
    proposal: Proposal,
    zone: ZoneId,
    onApply: (Notice, Proposal) -> Unit,
    onBeginOccurrence: (Notice, Proposal) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(proposal.reminder.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val from = proposal.from
        if (from != null) {
            Text(
                "${formatFullDayTime(from, zone)} → ${formatFullDayTime(proposal.to, zone)}",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "No occurrence near ${formatDay(proposal.date.atStartOfDay(zone).toInstant().toEpochMilli(), zone)}",
                Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text("“${proposal.matchedText}”", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)

        when (val refusal = proposal.refusal) {
            null ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onApply(notice, proposal) },
                        modifier = Modifier.semantics { contentDescription = "Apply ${proposal.reminder.name}" },
                    ) { Text("Apply") }
                    OutlinedButton(onClick = { onBeginOccurrence(notice, proposal) }) { Text("Different occurrence") }
                }
            is ProposalRefusal.NoOccurrence -> HintText("No occurrence near that date. Make a task from this notice instead.")
            is ProposalRefusal.Move -> {
                HintText(refusalText(refusal.reason))
                if (from != null) {
                    OutlinedButton(onClick = { onBeginOccurrence(notice, proposal) }, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Different occurrence")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)
}

private fun readingLabel(reading: ReadDate, zone: ZoneId): String =
    when (reading) {
        is ReadDate.Found -> {
            val day = reading.date.atStartOfDay(zone).toInstant().toEpochMilli()
            if (reading.time == null) "Date read: ${formatDay(day, zone)}" else "Date read: ${formatFullDayTime(reading.moment(zone), zone)}"
        }
        is ReadDate.Unread -> "No date found"
    }

private fun refusalText(reason: MoveRefusal): String =
    when (reason) {
        MoveRefusal.NotATask -> "Medication schedules don't move."
        MoveRefusal.NotAfter -> "That date is not after the task's moment."
        MoveRefusal.InThePast -> "That date has passed."
        MoveRefusal.TooLate -> "That moment is past its grace period."
        MoveRefusal.AlreadyAnswered -> "That moment is already answered."
        MoveRefusal.InProgress -> "That moment is open on another screen."
        MoveRefusal.InAPause -> "A pause covers that moment."
    }

private fun sourceLabel(kind: SourceKind, value: String): String =
    when (kind) {
        SourceKind.Email -> "Email · $value"
        SourceKind.App -> "App · $value"
        SourceKind.Share -> "Shared with TaskRx"
    }
