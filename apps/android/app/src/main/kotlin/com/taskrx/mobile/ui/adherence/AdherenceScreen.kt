package com.taskrx.mobile.ui.adherence

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskrx.core.adherence.ADHERENCE_PERIOD_DAYS
import com.taskrx.core.adherence.AdherenceStats
import com.taskrx.core.adherence.DayAdherence
import com.taskrx.mobile.format.barWeights
import com.taskrx.mobile.format.dayDescription
import com.taskrx.mobile.format.formatDayKey
import com.taskrx.mobile.format.formatPercent
import com.taskrx.mobile.format.fractionLabel
import com.taskrx.mobile.format.headlineDescription
import com.taskrx.mobile.format.isEmptyDay
import com.taskrx.mobile.format.periodLabel
import com.taskrx.mobile.format.snoozeNote
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.EmptyState
import com.taskrx.mobile.ui.components.ListScaffold
import com.taskrx.mobile.ui.components.SectionHeader

/**
 * The Adherence screen, drawn from [state]. Mirrors the render half of
 * apps/mobile/app/(app)/adherence.tsx. Tasks are absent by the calculator's rule, so a
 * productive week cannot disguise a bad medication one.
 */
@Composable
fun AdherenceContent(state: AdherenceUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val stats = state.reading?.stats

    ListScaffold(
        loading = state.loading,
        error = state.error,
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        skeletonRows = 6,
        loadingDescription = "Loading how it is going",
        modifier = modifier,
    ) {
        if (stats == null || stats.total == 0) {
            item {
                // Said plainly rather than shown as "0%", which is the shape of a week in
                // which every dose was missed. A person opening this on day one has not failed.
                EmptyState(
                    "Nothing to report yet",
                    "Once medications are due, the proportion you took over the last $ADHERENCE_PERIOD_DAYS days appears here.",
                    Modifier.fillParentMaxSize(),
                )
            }
        } else {
            item(key = "headline") { Headline(stats) }
            item(key = "stats") { StatsRow(stats) }
            snoozeNote(stats)?.let { note ->
                item(key = "snooze") {
                    Text(
                        note,
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        color = TaskRxColors.Snoozed,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item(key = "header") { SectionHeader("Day by day", Modifier.padding(top = 12.dp)) }
            items(stats.daily, key = { it.date }) { DayRow(it) }
        }
    }
}

@Composable
private fun Headline(stats: AdherenceStats) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            periodLabel(ADHERENCE_PERIOD_DAYS).uppercase(),
            color = TaskRxColors.Secondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            formatPercent(stats.percent),
            Modifier.semantics { contentDescription = headlineDescription(stats, ADHERENCE_PERIOD_DAYS) },
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(fractionLabel(stats), Modifier.padding(top = 4.dp), color = TaskRxColors.Secondary)
    }
}

@Composable
private fun StatsRow(stats: AdherenceStats) {
    HorizontalDivider(color = TaskRxColors.Divider)
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        // "Taken", not "Completed": ADR 0001 keeps the medication word in the UI.
        Stat("Taken", stats.completed, TaskRxColors.Completed, Modifier.weight(1f))
        Stat("Missed", stats.missed, TaskRxColors.Error, Modifier.weight(1f))
        Stat("Skipped", stats.skipped, TaskRxColors.Skipped, Modifier.weight(1f))
        Stat("Snoozed", stats.snoozed, TaskRxColors.Snoozed, Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "$label: $value" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, Modifier.padding(top = 2.dp), color = TaskRxColors.Secondary, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * A day of the period. A day with nothing recorded still appears, and as "None recorded"
 * rather than 0%: those are opposite facts, and on this surface the wrong one is an
 * accusation. ADR 0005: a gap in history "reads as no data rather than as a wall of Missed".
 */
@Composable
private fun DayRow(day: DayAdherence) {
    val empty = isEmptyDay(day)
    Row(
        Modifier
            .fillMaxWidth()
            // One sentence for a screen reader, not four fragments with a bar between them.
            .semantics(mergeDescendants = true) { contentDescription = dayDescription(day) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatDayKey(day.date),
            Modifier.width(112.dp),
            color = if (empty) TaskRxColors.Muted else Color.Unspecified,
            fontWeight = if (empty) FontWeight.Medium else FontWeight.SemiBold,
        )
        Track(day, empty, Modifier.weight(1f).padding(horizontal = 12.dp))
        if (empty) {
            Text("None recorded", Modifier.width(100.dp), color = TaskRxColors.Muted, fontStyle = FontStyle.Italic, textAlign = TextAlign.End)
        } else {
            Text(formatPercent(day.percent), Modifier.width(48.dp), fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            Text("${day.completed}/${day.total}", Modifier.width(44.dp), color = TaskRxColors.Secondary, textAlign = TextAlign.End)
        }
    }
    HorizontalDivider(color = TaskRxColors.Divider)
}

/** A bar as well as the numbers: a week of percentages has to be read line by line, a week of bars is one glance. */
@Composable
private fun Track(day: DayAdherence, empty: Boolean, modifier: Modifier) {
    Row(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(TaskRxColors.Divider)) {
        if (!empty) {
            val (filled, rest) = barWeights(day)
            // A zero weight is refused by the layout, and a 0% or 100% day has one.
            if (filled > 0f) Box(Modifier.weight(filled).fillMaxHeight().background(TaskRxColors.Medication))
            if (rest > 0f) Box(Modifier.weight(rest).fillMaxHeight())
        }
    }
}
