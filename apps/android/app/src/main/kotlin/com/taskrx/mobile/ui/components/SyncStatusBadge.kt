package com.taskrx.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskrx.core.sync.SyncBadge

/** The words a screen reader gets for [badge]. */
fun syncBadgeDescription(badge: SyncBadge): String =
    when (badge) {
        SyncBadge.Synced -> "Synced"
        SyncBadge.Pending -> "Changes waiting to sync"
        SyncBadge.Syncing -> "Syncing"
        SyncBadge.Offline -> "Offline"
        SyncBadge.Error -> "Sync error"
    }

/**
 * The header's sync badge: a small round mark, the colours and glyphs of the Expo
 * `SyncStatusBadge`. Never the only signal - the description is spoken.
 */
@Composable
fun SyncStatusBadge(badge: SyncBadge, modifier: Modifier = Modifier, onProblems: (() -> Unit)? = null) {
    val (tint, glyph, mark) =
        when (badge) {
            SyncBadge.Synced -> Triple(Color(0xFFE6F9EE), "✓", Color(0xFF34C759))
            SyncBadge.Pending -> Triple(Color(0xFFFFF8E6), "⏱", Color(0xFFFF9500))
            SyncBadge.Syncing -> Triple(Color(0xFFE6F4FE), "", Color(0xFF007AFF))
            SyncBadge.Offline -> Triple(Color(0xFFF2F2F7), "☁", Color(0xFF8E8E93))
            SyncBadge.Error -> Triple(Color(0xFFFFE6E6), "!", Color(0xFFCC0000))
        }
    Box(
        modifier
            .padding(end = 8.dp)
            .size(24.dp)
            .clip(CircleShape)
            .background(tint)
            // Only the error mark is a button: it is the one that has somewhere to go.
            .then(if (badge == SyncBadge.Error && onProblems != null) Modifier.clickable(onClick = onProblems) else Modifier)
            .semantics { contentDescription = syncBadgeDescription(badge) },
        contentAlignment = Alignment.Center,
    ) {
        if (badge == SyncBadge.Syncing) {
            CircularProgressIndicator(Modifier.size(14.dp), color = mark, strokeWidth = 2.dp)
        } else {
            Text(glyph, color = mark, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
