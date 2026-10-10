package com.taskrx.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The palette the Expo app used, so the two builds read as one product: a blue accent, a
 * green edge for a medication and an amber one for a task. The Kind colours are never the
 * only signal (see `kindLabel`); they carry a fast scan and the word carries everything else.
 */
object TaskRxColors {
    val Accent = Color(0xFF007AFF)
    val AccentDisabled = Color(0xFFAAC8EE)
    val Medication = Color(0xFF0B8043)
    val MedicationTint = Color(0xFFE6F4EA)
    val Task = Color(0xFF8A6D00)
    val TaskTint = Color(0xFFFDF3D8)
    val NextTint = Color(0xFFF2F8FF)
    val Muted = Color(0xFF999999)
    val Secondary = Color(0xFF666666)
    val Error = Color(0xFFCC0000)
    val ErrorTint = Color(0xFFFDECEA)
    val Completed = Color(0xFF137333)
    val Skipped = Color(0xFF8A6D00)
    val Snoozed = Color(0xFF5B3FA8)
    val Divider = Color(0xFFEEEEEE)
    val CompleteButton = Color(0xFF34C759)
    val SkipButton = Color(0xFFFF9500)
    val SnoozeButton = Color(0xFF5856D6)
}

@Composable
fun TaskRxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            lightColorScheme(
                primary = TaskRxColors.Accent,
                error = TaskRxColors.Error,
            ),
        content = content,
    )
}
