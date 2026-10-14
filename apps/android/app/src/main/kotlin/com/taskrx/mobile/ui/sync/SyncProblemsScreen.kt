package com.taskrx.mobile.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ErrorText
import com.taskrx.mobile.ui.components.PrimaryButton

/**
 * What the sync set aside after five failed tries, and the button that sends it all again.
 * Nothing here has been lost: each change is still on the phone.
 */
@Composable
fun SyncProblemsContent(state: SyncProblemsState, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (!state.loading && state.items.isEmpty() && state.error == null) {
        EmptyState("Nothing is stuck", "No change has been set aside. Anything still waiting is being sent.", modifier.fillMaxSize())
        return
    }
    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "These changes could not be sent after five tries, so they were set aside. Nothing is lost - they are still on this phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        state.error?.let { ErrorText(it) }
        PrimaryButton("Try again", onRetry, Modifier.padding(vertical = 12.dp), enabled = state.items.isNotEmpty(), busy = state.retrying)
        LazyColumn {
            items(state.items, key = { it.entryId }) { change ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(change.what, fontWeight = FontWeight.SemiBold)
                    Text(change.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}
