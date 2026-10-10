package com.taskrx.mobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import com.taskrx.mobile.ui.TaskRxColors

/**
 * The shape every list screen shares: a skeleton while it reads, a failure with a way to
 * try again, a pull-to-refresh body otherwise. [empty] is drawn when there are no items,
 * inside the refresh box so an empty list can still be pulled.
 *
 * The retry is a button as well as the pull gesture: a failed screen with no visible way
 * out reads as broken, and a pull is not discoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScaffold(
    loading: Boolean,
    error: String?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    skeletonRows: Int,
    loadingDescription: String,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    when {
        loading -> SkeletonList(skeletonRows, loadingDescription, modifier)
        error != null ->
            Centered(modifier) {
                Text("Failed to load: $error", color = TaskRxColors.Error, textAlign = TextAlign.Center)
                TextButton(onClick = onRefresh) { Text("Try again") }
            }
        else ->
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = state, content = content)
            }
    }
}

/** An empty list's title and body, centred in what is left. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Centered(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, Modifier.padding(top = 8.dp), color = TaskRxColors.Secondary, textAlign = TextAlign.Center)
    }
}

/** The grey band that heads a group of rows. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Surface(color = Color(0xFFF5F5F5), modifier = modifier.fillMaxWidth()) {
        Text(
            title.uppercase(),
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFF444444),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}
