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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.snooze.SNOOZE_CHOICES_MINUTES
import com.taskrx.mobile.ui.TaskRxColors

/**
 * Settings: one choice so far, how long a snooze lasts. It applies to the answer screen and
 * to the Snooze buttons in the notification shade, and to medications and tasks alike. A
 * snooze already set keeps the time it was set for.
 */
@Composable
fun SettingsContent(snoozeMinutes: Int, onSnoozeMinutes: (Int) -> Unit, modifier: Modifier = Modifier) {
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
    }
}

/** "5 minutes", "1 hour": a snooze length as the list shows it. */
fun snoozeLabel(minutes: Int): String = if (minutes == 60) "1 hour" else "$minutes minutes"
