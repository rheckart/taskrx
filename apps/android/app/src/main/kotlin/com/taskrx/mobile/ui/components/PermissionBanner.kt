package com.taskrx.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taskrx.core.notifications.PermissionGap
import com.taskrx.core.notifications.PermissionSeverity
import com.taskrx.mobile.ui.TaskRxColors

/**
 * Says out loud that reminders cannot arrive. Mirrors NotificationPermissionBanner.tsx.
 *
 * The subject is whether ANY of the rows below can reach the person at all (issue #20's
 * silent state, where every alarm fires and Android discards every notification), so it
 * sits above the day, not among it. It decides nothing: the wording and the remedy are
 * :core's `derivePermissionGap`, resolved by the permission gate. Nothing is drawn when
 * reminders can arrive.
 *
 * Two severities, styled apart, because they are different sentences: a blocking gap means
 * nothing reaches you, a degraded one means it reaches you late. Painting both red would
 * teach people to scroll past the one that means something.
 */
@Composable
fun PermissionBanner(gap: PermissionGap?, onResolve: (PermissionGap) -> Unit, modifier: Modifier = Modifier) {
    if (gap == null) return
    val blocking = gap.severity == PermissionSeverity.Blocking
    val edge = if (blocking) TaskRxColors.Error else TaskRxColors.Task

    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (blocking) TaskRxColors.ErrorTint else TaskRxColors.TaskTint),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(edge))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            // One announcement: the title and the body read as a single alert, and the
            // button below stays its own target with its own label.
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Assertive
                    contentDescription = "${gap.title}. ${gap.body}"
                },
            ) {
                Text(
                    gap.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (blocking) TaskRxColors.Error else MaterialTheme.colorScheme.onSurface,
                )
                Text(gap.body, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = { onResolve(gap) }) { Text(gap.remedyLabel, fontWeight = FontWeight.SemiBold) }
        }
    }
}
