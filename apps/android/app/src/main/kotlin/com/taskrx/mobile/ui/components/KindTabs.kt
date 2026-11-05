package com.taskrx.mobile.ui.components

import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.taskrx.core.domain.ReminderKind

/** The tab's word for a Kind: plural, because it names a list. */
fun tabWord(kind: ReminderKind): String =
    when (kind) {
        ReminderKind.Medication -> "Medications"
        ReminderKind.Task -> "Tasks"
    }

/**
 * Two tabs, Medications first, so the two Kinds are never read as one pile. [counts] is how
 * many in each still want an answer; a count of zero is left off, so a number on a tab always
 * means something is waiting there.
 */
@Composable
fun KindTabs(
    selected: ReminderKind,
    counts: Map<ReminderKind, Int>,
    onSelect: (ReminderKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = ReminderKind.entries.indexOf(selected), modifier = modifier) {
        for (kind in listOf(ReminderKind.Medication, ReminderKind.Task)) {
            val count = counts[kind] ?: 0
            val word = tabWord(kind)
            Tab(
                selected = kind == selected,
                onClick = { onSelect(kind) },
                modifier = Modifier.semantics { contentDescription = if (count > 0) "$word, $count to do" else word },
                text = {
                    Text(
                        if (count > 0) "$word ($count)" else word,
                        fontWeight = if (kind == selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }
    }
}
