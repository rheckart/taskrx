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
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.forms.InstantField

/**
 * Notices a watched source admitted. Each shows where it came from, its subject and the
 * sentence that was kept, then what the person can do with it: make a one-off task at a moment
 * they pick, or put it aside. Nothing moves on its own, and no date has been read from the
 * text yet - the person sets it.
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
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    val draft = state.draft
    if (draft != null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            FieldLabel("From this notice", Modifier.padding(top = 0.dp))
            Text(draft.notice.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            draft.notice.sentence?.let { Text(it, Modifier.padding(top = 4.dp), color = TaskRxColors.Secondary) }
            InstantField(
                label = "When",
                hint = "The date the notice names. Nothing was read from its text.",
                value = draft.at,
                onChange = onDraftAt,
                now = now,
            )
            PrimaryButton("Add task", onSaveTask, Modifier.padding(top = 24.dp), busy = state.saving)
            TextButton(onClick = onCancelTask, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
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
                NoticeRow(notice, onBeginTask, onDismiss)
            }
        }
    }
}

@Composable
private fun NoticeRow(notice: Notice, onBeginTask: (Notice) -> Unit, onDismiss: (Notice) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(sourceLabel(notice.sourceKind, notice.sourceValue), style = MaterialTheme.typography.labelMedium, color = TaskRxColors.Secondary)
        Text(notice.title, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        notice.sentence?.let {
            Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text("Received ${formatFullDayTime(notice.receivedAt)}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)
        when (notice.resolution) {
            NoticeResolution.None ->
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onBeginTask(notice) },
                        modifier = Modifier.semantics { contentDescription = "New task from ${notice.title}" },
                    ) { Text("New task") }
                    OutlinedButton(
                        onClick = { onDismiss(notice) },
                        modifier = Modifier.semantics { contentDescription = "Dismiss ${notice.title}" },
                    ) { Text("Dismiss") }
                }
            NoticeResolution.Applied -> StatusLine("Turned into a task")
            NoticeResolution.Dismissed -> StatusLine("Dismissed")
        }
        HorizontalDivider(Modifier.padding(top = 12.dp), color = TaskRxColors.Divider)
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = TaskRxColors.Secondary)
}

private fun sourceLabel(kind: SourceKind, value: String): String =
    when (kind) {
        SourceKind.Email -> "Email · $value"
        SourceKind.App -> "App · $value"
        SourceKind.Share -> "Shared with TaskRx"
    }
