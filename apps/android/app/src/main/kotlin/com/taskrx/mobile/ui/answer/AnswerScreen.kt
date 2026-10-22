package com.taskrx.mobile.ui.answer

import com.taskrx.core.domain.InstanceStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taskrx.core.snooze.canPickATimeToday
import com.taskrx.mobile.ui.forms.TimeDialog
import com.taskrx.mobile.format.formatDayTime
import com.taskrx.mobile.format.isOverdue
import com.taskrx.mobile.format.urgencyLabel
import com.taskrx.mobile.format.wordsFor
import com.taskrx.mobile.ui.TaskRxColors
import com.taskrx.mobile.ui.components.PrimaryButton

/**
 * The answer surface, drawn from [state]. Mirrors the render half of
 * archive/expo-rn:apps/mobile/app/(app)/dose/log.tsx. [now] is read for the urgency line only.
 *
 * Kind-neutral in structure, Kind-specific only in words: a medication reads "Mark taken",
 * a task "Mark done" (see `wordsFor`), and the stored answer is Completed either way.
 */
@Composable
fun AnswerContent(
    state: AnswerState,
    now: Long,
    snoozeMinutes: Int,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
    onReschedule: (hour: Int, minute: Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        AnswerState.Loading ->
            Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading" })
            }
        AnswerState.InvalidLink ->
            Problem(
                "Invalid reminder link",
                "Open this screen from your timeline or from a reminder notification.",
                onDone,
                modifier,
            )
        is AnswerState.Failed -> Problem("Could not load this reminder", state.message, onDone, modifier)
        is AnswerState.Loaded -> Loaded(state, now, snoozeMinutes, onComplete, onSkip, onSnooze, onReschedule, onDone, modifier)
    }
}

@Composable
private fun Problem(title: String, body: String, onDone: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, Modifier.padding(top = 8.dp), color = TaskRxColors.Secondary, textAlign = TextAlign.Center)
        PrimaryButton("Go to my day", onDone, Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Loaded(
    state: AnswerState.Loaded,
    now: Long,
    snoozeMinutes: Int,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
    onReschedule: (hour: Int, minute: Int) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val reminder = state.reminder
    val words = wordsFor(reminder.kind)
    val idle = state.action == Action.Idle

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        // The card is one thing to a screen reader: what it is, which, when, and how urgent.
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(TaskRxColors.Panel)
                .padding(20.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(words.badge, color = TaskRxColors.Secondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(reminder.name, Modifier.padding(top = 6.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            reminder.dosage?.let { Text(it, Modifier.padding(top = 4.dp), color = TaskRxColors.Secondary, fontSize = 16.sp) }
            Text("Scheduled: ${formatDayTime(state.scheduledTime)}", Modifier.padding(top = 12.dp), color = TaskRxColors.BodyText)
            Text(
                urgencyLabel(state.scheduledTime, now, words),
                Modifier.padding(top = 8.dp),
                color = if (isOverdue(state.scheduledTime, now)) TaskRxColors.Error else TaskRxColors.Accent,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }

        if (state.action == Action.Done && state.recordedAt != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TaskRxColors.SuccessTint)
                    .padding(24.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Recorded", color = TaskRxColors.SuccessText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(formatDayTime(state.recordedAt), Modifier.padding(top = 4.dp), color = TaskRxColors.Secondary)
                PrimaryButton("Done", onDone, Modifier.padding(top = 16.dp))
            }
        } else {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(words.complete, onComplete, enabled = idle, busy = state.action == Action.Completing, color = TaskRxColors.CompleteButton)
                PrimaryButton("Skip", onSkip, enabled = idle, busy = state.action == Action.Skipping, color = TaskRxColors.SkipButton)
                // A missed moment is over: there is nothing left to put off.
                if (state.instance.status != InstanceStatus.Missed) {
                    PrimaryButton(
                        "Snooze $snoozeMinutes min",
                        onSnooze,
                        enabled = idle,
                        busy = state.action == Action.Snoozing,
                        color = TaskRxColors.SnoozeButton,
                    )
                    // Later today only: a deferral past midnight belongs to the next day's timeline.
                    if (canPickATimeToday(now)) {
                        PrimaryButton(
                            "Pick a time",
                            { picking = true },
                            enabled = idle,
                            color = TaskRxColors.SnoozeButton,
                        )
                    }
                }
            }
        }

        state.error?.let { Text(it, Modifier.fillMaxWidth().padding(top = 16.dp), color = TaskRxColors.Error, textAlign = TextAlign.Center) }
    }

    if (picking) {
        // Opens on the time a plain snooze would land on, the likeliest thing someone wants to nudge.
        val start = java.time.Instant.ofEpochMilli(now + snoozeMinutes * 60_000L).atZone(java.time.ZoneId.systemDefault())
        TimeDialog(
            hour = start.hour,
            minute = start.minute,
            onDismiss = { picking = false },
            onConfirm = { hour, minute ->
                picking = false
                onReschedule(hour, minute)
            },
        )
    }
}
