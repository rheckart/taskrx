package com.taskrx.mobile.ui.reminders

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.WatchedSource
import com.taskrx.mobile.format.formatFullDayTime
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.Centered
import com.taskrx.mobile.ui.components.ChoiceRow
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.PrimaryButton
import com.taskrx.mobile.ui.forms.ReminderFields

/** The edit form, drawn from [state]. The same fields as the new-reminder form, started from what is stored. */
@Composable
fun EditReminderContent(
    state: EditState,
    vm: EditReminderViewModel,
    modifier: Modifier = Modifier,
    now: () -> Long = System::currentTimeMillis,
) {
    when (state) {
        EditState.Loading -> Centered(modifier) { Text("Loading…") }
        EditState.NotFound ->
            Centered(modifier) {
                Text("That reminder is not here any more.")
            }
        is EditState.Editing ->
            Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                ReminderFields(
                    kind = state.reminder.kind,
                    name = state.name,
                    onName = vm::setName,
                    detail = state.detail,
                    onDetail = vm::setDetail,
                    schedule = state.schedule,
                    onSchedule = vm::setSchedule,
                    now = now,
                )
                if (state.reminder.kind == ReminderKind.Task && state.reminder.overrides.isNotEmpty()) {
                    MovedOccurrences(state.reminder, vm::removeOverride, now = now)
                }
                if (state.reminder.kind == ReminderKind.Task) {
                    NoticeRules(state, vm)
                }
                state.error?.let { ErrorText("Save failed: $it", Modifier.padding(top = 16.dp)) }
                PrimaryButton("Save changes", vm::save, Modifier.padding(top = 24.dp), enabled = state.canSave, busy = state.saving)
            }
    }
}

/**
 * The occurrences of this task that were moved off their scheduled moment.
 *
 * Shown so the list never becomes a mystery: an override whose source the Schedule no longer
 * produces (because the rule was edited) is inert, and this is where it can be removed. Each
 * row names both moments, because "moved" alone does not say from where.
 */
@Composable
private fun MovedOccurrences(
    reminder: Reminder,
    onRemove: (Long) -> Unit,
    now: () -> Long,
) {
    FieldLabel("Moved occurrences", Modifier.padding(top = 24.dp))
    HintText("One moment each. The schedule itself is unchanged.")
    for (override in reminder.overrides) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(formatFullDayTime(override.to), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "was ${formatFullDayTime(override.from)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskRxColors.Secondary,
                )
            }
            TextButton(
                onClick = { onRemove(override.from) },
                modifier = Modifier.semantics { contentDescription = "Remove the move from ${formatFullDayTime(override.from)}" },
            ) { Text("Remove") }
        }
    }
}

/**
 * The rules that let a notice be proposed against this task: a watched source, and the words
 * that make a notice about it. A rule proposes; it never applies. An empty set of words means
 * "any notice from this source".
 */
@Composable
private fun NoticeRules(state: EditState.Editing, vm: EditReminderViewModel) {
    FieldLabel("Notices about this", Modifier.padding(top = 24.dp))
    HintText("A notice from a watched source that mentions one of these words may be proposed against this task. Nothing moves until you agree.")

    for ((index, rule) in state.reminder.noticeRules.withIndex()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ruleDescription(rule), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (rule.terms.isEmpty()) "Any notice from this source" else rule.terms.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TaskRxColors.Secondary,
                )
            }
            TextButton(
                onClick = { vm.removeNoticeRule(index) },
                modifier = Modifier.semantics { contentDescription = "Remove the rule for ${rule.sourceValue}" },
            ) { Text("Remove") }
        }
    }

    if (state.watchedSources.isEmpty()) {
        HintText("Add a sender or app in Settings first. A share needs no rule.")
        return
    }

    var selected by rememberSaveable { mutableStateOf(0) }
    var terms by rememberSaveable { mutableStateOf("") }
    val source = state.watchedSources[selected.coerceIn(0, state.watchedSources.lastIndex)]
    ChoiceRow(
        state.watchedSources.map { it to it.label },
        source,
        { picked -> selected = state.watchedSources.indexOfFirst { it.id == picked.id }.coerceAtLeast(0) },
        Modifier.padding(top = 8.dp),
    )
    OutlinedTextField(
        value = terms,
        onValueChange = { terms = it },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        singleLine = true,
        placeholder = { Text("Words to match, e.g. collection, bins") },
    )
    TextButton(
        onClick = { vm.addNoticeRule(source, terms) },
        modifier = Modifier.semantics { contentDescription = "Add a rule for ${source.label}" },
    ) { Text("Add rule") }
}

private fun ruleDescription(rule: NoticeRule): String =
    when (rule.sourceKind) {
        com.taskrx.core.domain.SourceKind.Email -> "Email · ${rule.sourceValue}"
        com.taskrx.core.domain.SourceKind.App -> "App · ${rule.sourceValue}"
        com.taskrx.core.domain.SourceKind.Share -> "Shared with TaskRx"
    }
