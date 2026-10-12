package com.taskrx.mobile.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.Centered

/** The search box and what it found, drawn from [state]. [onPick] is told the match; where it goes is the caller's. */
@Composable
fun DrugSearchContent(
    state: DrugSearchState,
    onQuery: (String) -> Unit,
    onPick: (DrugMatch) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            label = { Text("Drug name") },
            placeholder = { Text("Search RxNorm (e.g. metformin)") },
        )
        when (val status = state.status) {
            SearchStatus.Idle -> Hint("Type a drug name to search RxNorm.")
            SearchStatus.Searching -> Centered { CircularProgressIndicator() }
            SearchStatus.Unavailable ->
                Centered {
                    Text("Drug search is not available offline", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(
                        "It looks names up on a server. You can still add a medication by typing its name.",
                        Modifier.padding(top = 8.dp),
                        color = TaskRxColors.Secondary,
                        textAlign = TextAlign.Center,
                    )
                }
            is SearchStatus.Failed -> Centered { Text("Search failed: ${status.message}", color = TaskRxColors.Error, textAlign = TextAlign.Center) }
            is SearchStatus.Done ->
                if (status.results.isEmpty()) {
                    Hint("No matches for “${state.query.trim()}”.")
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(status.results, key = { it.rxcui }) { match ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(role = Role.Button, onClick = { onPick(match) })
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                Text(match.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text("RxCUI ${match.rxcui}", color = TaskRxColors.Secondary, style = MaterialTheme.typography.bodySmall)
                            }
                            HorizontalDivider(color = TaskRxColors.Divider)
                        }
                    }
                }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Centered { Text(text, color = TaskRxColors.Secondary, textAlign = TextAlign.Center) }
}
