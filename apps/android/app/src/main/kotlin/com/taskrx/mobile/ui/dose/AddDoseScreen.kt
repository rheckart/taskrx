package com.taskrx.mobile.ui.dose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.domain.Reminder
import com.taskrx.mobile.format.formatTime
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.forms.TimeDialog
import java.time.Instant
import java.time.ZoneId

/**
 * Pick a medication, say when you took it (it opens on now), record it. Drawn from [state].
 * [now] is a parameter so the time it opens on is the app's clock, not the device's.
 */
@Composable
fun AddDoseContent(
    state: AddDoseState,
    onRefresh: () -> Unit,
    onRecord: (Reminder, Int, Int) -> Unit,
    now: () -> Long,
    modifier: Modifier = Modifier,
) {
    var picked by remember { mutableStateOf<Reminder?>(null) }
    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        skeletonRows = 5,
        loadingDescription = "Loading your medications",
        modifier = modifier,
    ) {
        if (state.medications.isEmpty()) {
            item {
                EmptyState("No medications yet", "Add a medication first, then you can record a dose of it here.", Modifier.fillParentMaxSize())
            }
        } else {
            items(state.medications, key = { it.id }) { medication ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Record a dose") { picked = medication }) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(medication.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        val detail = listOfNotNull(medication.dosage, if (medication.active) null else "Stopped").joinToString(" · ")
                        if (detail.isNotEmpty()) Text(detail, color = TaskRxColors.Secondary)
                    }
                    HorizontalDivider(color = TaskRxColors.Divider)
                }
            }
        }
    }
    picked?.let { medication -> RecordDialog(medication, now(), onDismiss = { picked = null }, onRecord = { h, m -> picked = null; onRecord(medication, h, m) }) }
}

@Composable
private fun RecordDialog(medication: Reminder, now: Long, onDismiss: () -> Unit, onRecord: (Int, Int) -> Unit) {
    val start = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
    var hour by remember { mutableStateOf(start.hour) }
    var minute by remember { mutableStateOf(start.minute) }
    var changing by remember { mutableStateOf(false) }
    if (changing) {
        TimeDialog(hour, minute, onDismiss = { changing = false }, onConfirm = { h, m -> hour = h; minute = m; changing = false })
        return
    }
    val taken = formatTime(start.toLocalDate().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Record ${medication.name}") },
        text = { Text("Taken today at $taken") },
        confirmButton = { TextButton(onClick = { onRecord(hour, minute) }) { Text("Record") } },
        dismissButton = {
            Column {
                TextButton(onClick = { changing = true }) { Text("Change time") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
