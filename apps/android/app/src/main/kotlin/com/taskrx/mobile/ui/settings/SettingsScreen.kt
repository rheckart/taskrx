package com.taskrx.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.WatchedSource
import com.taskrx.core.snooze.SNOOZE_CHOICES_MINUTES
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.ChoiceRow
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.FieldLabel
import com.taskrx.mobile.ui.components.HintText
import com.taskrx.mobile.ui.components.PrimaryButton

/**
 * Settings: one choice so far, how long a snooze lasts. It applies to the answer screen and
 * to the Snooze buttons in the notification shade, and to medications and tasks alike. A
 * snooze already set keeps the time it was set for.
 */
@Composable
fun SettingsContent(
    snoozeMinutes: Int,
    onSnoozeMinutes: (Int) -> Unit,
    modifier: Modifier = Modifier,
    watchedSources: List<WatchedSource> = emptyList(),
    watchedLoading: Boolean = false,
    watchedError: String? = null,
    adding: Boolean = false,
    onStartAdd: () -> Unit = {},
    onCancelAdd: () -> Unit = {},
    newKind: SourceKind = SourceKind.Email,
    onNewKind: (SourceKind) -> Unit = {},
    newValue: String = "",
    onNewValue: (String) -> Unit = {},
    newLabel: String = "",
    onNewLabel: (String) -> Unit = {},
    canSaveSource: Boolean = false,
    onSaveSource: () -> Unit = {},
    onRemoveSource: (WatchedSource) -> Unit = {},
    listenerAllowed: Boolean = false,
    onOpenListenerSettings: () -> Unit = {},
    seenApps: List<String> = emptyList(),
    onApproveSeenApp: (String) -> Unit = {},
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Snooze length", fontWeight = FontWeight.SemiBold)
        Text(
            "How long Snooze puts a reminder off. A snooze you already set keeps its time.",
            Modifier.padding(top = 4.dp, bottom = 8.dp),
            color = TaskRxColors.Secondary,
        )
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (minutes in SNOOZE_CHOICES_MINUTES) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = minutes == snoozeMinutes, role = Role.RadioButton, onClick = { onSnoozeMinutes(minutes) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The row is the control; the button only draws the state.
                    RadioButton(selected = minutes == snoozeMinutes, onClick = null)
                    Text(snoozeLabel(minutes), Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
                }
            }
        }

        FieldLabel("Watched senders and apps", Modifier.padding(top = 32.dp))
        HintText("Only notices from these are kept. Watching one moves nothing: a reminder's own “Notices about this” decides what may be proposed.")
        watchedError?.let { ErrorText("Couldn't load them: $it") }
        if (watchedLoading) {
            Text("Loading…", Modifier.padding(top = 8.dp), color = TaskRxColors.Secondary)
        }
        for (source in watchedSources) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(source.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (source.kind == SourceKind.Email) source.value else "App · ${source.value}",
                        color = TaskRxColors.Secondary,
                    )
                }
                TextButton(onClick = { onRemoveSource(source) }) { Text("Remove", color = TaskRxColors.Error) }
            }
        }
        if (adding) {
            FieldLabel("What to watch")
            ChoiceRow(
                listOf(SourceKind.Email to "Email sender", SourceKind.App to "App"),
                newKind,
                onNewKind,
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = onNewValue,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text(if (newKind == SourceKind.Email) "waste@city.example" else "com.example.app") },
            )
            OutlinedTextField(
                value = newLabel,
                onValueChange = onNewLabel,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                placeholder = { Text("A name you'll recognise") },
            )
            PrimaryButton("Watch it", onSaveSource, Modifier.padding(top = 16.dp), enabled = canSaveSource)
            TextButton(onClick = onCancelAdd, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") }
        } else {
            TextButton(onClick = onStartAdd, Modifier.padding(top = 8.dp)) { Text("+ Add a sender or app") }
        }

        FieldLabel("Notifications", Modifier.padding(top = 32.dp))
        HintText(
            "With notification access, TaskRx sees every notification and keeps a package name from each. " +
                "It reads the text only of an app you watch below.",
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (listenerAllowed) "On" else "Off", fontWeight = FontWeight.SemiBold)
                Text(
                    if (listenerAllowed) "TaskRx can read app notifications." else "TaskRx cannot read app notifications yet.",
                    color = TaskRxColors.Secondary,
                )
            }
            TextButton(onClick = onOpenListenerSettings) { Text(if (listenerAllowed) "Change" else "Turn on") }
        }
        if (seenApps.isNotEmpty()) {
            FieldLabel("Apps seen recently", Modifier.padding(top = 16.dp))
            HintText("Approve one to keep its notices. Until you do, TaskRx has kept only the package name.")
            for (app in seenApps) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(app, Modifier.weight(1f))
                    TextButton(onClick = { onApproveSeenApp(app) }) { Text("Watch") }
                }
            }
        }
    }
}

/** "5 minutes", "1 hour": a snooze length as the list shows it. */
fun snoozeLabel(minutes: Int): String = if (minutes == 60) "1 hour" else "$minutes minutes"
