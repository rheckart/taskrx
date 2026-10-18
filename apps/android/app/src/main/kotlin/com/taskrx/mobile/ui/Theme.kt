package com.taskrx.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One set of colours for light and one for dark. Screens read them through [TaskRxColors],
 * which looks the current palette up, so a screen never asks which mode it is in.
 */
data class TaskRxPalette(
    val accent: Color,
    val accentDisabled: Color,
    val medication: Color,
    val medicationTint: Color,
    val task: Color,
    val taskTint: Color,
    val nextTint: Color,
    val muted: Color,
    val secondary: Color,
    val bodyText: Color,
    val error: Color,
    val errorTint: Color,
    val completed: Color,
    val skipped: Color,
    val snoozed: Color,
    val divider: Color,
    val completeButton: Color,
    val skipButton: Color,
    val snoozeButton: Color,
    val panel: Color,
    val panelBlue: Color,
    val warnTint: Color,
    val warnText: Color,
    val successTint: Color,
    val successText: Color,
    val outline: Color,
    val badgeSyncedTint: Color,
    val badgeSynced: Color,
    val badgePendingTint: Color,
    val badgePending: Color,
    val badgeSyncingTint: Color,
    val badgeSyncing: Color,
    val badgeOfflineTint: Color,
    val badgeOffline: Color,
    val badgeErrorTint: Color,
    val badgeError: Color,
    val snoozedStatus: Color,
)

/** The palette the Expo app used, so the two builds read as one product. */
val LightPalette =
    TaskRxPalette(
        accent = Color(0xFF007AFF),
        accentDisabled = Color(0xFFAAC8EE),
        medication = Color(0xFF0B8043),
        medicationTint = Color(0xFFE6F4EA),
        task = Color(0xFF8A6D00),
        taskTint = Color(0xFFFDF3D8),
        nextTint = Color(0xFFF2F8FF),
        muted = Color(0xFF999999),
        secondary = Color(0xFF666666),
        bodyText = Color(0xFF444444),
        error = Color(0xFFCC0000),
        errorTint = Color(0xFFFDECEA),
        completed = Color(0xFF137333),
        skipped = Color(0xFF8A6D00),
        snoozed = Color(0xFF5B3FA8),
        divider = Color(0xFFEEEEEE),
        completeButton = Color(0xFF34C759),
        skipButton = Color(0xFFFF9500),
        snoozeButton = Color(0xFF5856D6),
        panel = Color(0xFFF5F5F5),
        panelBlue = Color(0xFFF5F7FA),
        warnTint = Color(0xFFFFF4E5),
        warnText = Color(0xFF5C4400),
        successTint = Color(0xFFE8F5E9),
        successText = Color(0xFF2E7D32),
        outline = Color(0xFFCCCCCC),
        badgeSyncedTint = Color(0xFFE6F9EE),
        badgeSynced = Color(0xFF34C759),
        badgePendingTint = Color(0xFFFFF8E6),
        badgePending = Color(0xFFFF9500),
        badgeSyncingTint = Color(0xFFE6F4FE),
        badgeSyncing = Color(0xFF007AFF),
        badgeOfflineTint = Color(0xFFF2F2F7),
        badgeOffline = Color(0xFF8E8E93),
        badgeErrorTint = Color(0xFFFFE6E6),
        badgeError = Color(0xFFCC0000),
        snoozedStatus = Color(0xFF8E8E93),
    )

/** Dark tints are dark and muted; accents are lighter so text on them keeps 4.5:1. */
val DarkPalette =
    TaskRxPalette(
        accent = Color(0xFF4DA3FF),
        accentDisabled = Color(0xFF2A4260),
        medication = Color(0xFF5FD38D),
        medicationTint = Color(0xFF16301F),
        task = Color(0xFFE5C35A),
        taskTint = Color(0xFF35290A),
        nextTint = Color(0xFF14263A),
        muted = Color(0xFF9A9A9A),
        secondary = Color(0xFFB0B0B0),
        bodyText = Color(0xFFD0D0D0),
        error = Color(0xFFFF6B6B),
        errorTint = Color(0xFF3A1B1B),
        completed = Color(0xFF6FD08C),
        skipped = Color(0xFFE5C35A),
        snoozed = Color(0xFFB9A6F0),
        divider = Color(0xFF2E2E2E),
        completeButton = Color(0xFF2FB350),
        skipButton = Color(0xFFE08600),
        snoozeButton = Color(0xFF6C6AE0),
        panel = Color(0xFF242424),
        panelBlue = Color(0xFF1F2530),
        warnTint = Color(0xFF3A2C10),
        warnText = Color(0xFFF2D58A),
        successTint = Color(0xFF16301F),
        successText = Color(0xFF8FE0A8),
        outline = Color(0xFF555555),
        badgeSyncedTint = Color(0xFF16301F),
        badgeSynced = Color(0xFF5FD38D),
        badgePendingTint = Color(0xFF3A2C10),
        badgePending = Color(0xFFFFB23F),
        badgeSyncingTint = Color(0xFF14263A),
        badgeSyncing = Color(0xFF4DA3FF),
        badgeOfflineTint = Color(0xFF2A2A2E),
        badgeOffline = Color(0xFFA0A0A8),
        badgeErrorTint = Color(0xFF3A1B1B),
        badgeError = Color(0xFFFF6B6B),
        snoozedStatus = Color(0xFFA0A0A8),
    )

/** Light by default, so a screen rendered in a test without the theme still has colours. */
val LocalTaskRxPalette = staticCompositionLocalOf { LightPalette }

/**
 * The names screens already use. Each reads the current palette, so the call sites did not
 * change when dark mode came in. They need a composition to read from.
 */
object TaskRxColors {
    val Accent: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.accent
    val AccentDisabled: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.accentDisabled
    val Medication: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.medication
    val MedicationTint: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.medicationTint
    val Task: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.task
    val TaskTint: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.taskTint
    val NextTint: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.nextTint
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.muted
    val Secondary: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.secondary
    val BodyText: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.bodyText
    val Error: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.error
    val ErrorTint: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.errorTint
    val Completed: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.completed
    val Skipped: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.skipped
    val Snoozed: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.snoozed
    val Divider: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.divider
    val CompleteButton: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.completeButton
    val SkipButton: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.skipButton
    val SnoozeButton: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.snoozeButton
    val Panel: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.panel
    val PanelBlue: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.panelBlue
    val WarnTint: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.warnTint
    val WarnText: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.warnText
    val SuccessTint: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.successTint
    val SuccessText: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.successText
    // White text sits on these in both modes, so they are the same in both.
    val ToastSuccess = Color(0xFF2E7D32)
    val ToastError = Color(0xFFC62828)
    val ToastInfo = Color(0xFF1565C0)
    val Outline: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.outline
    val SnoozedStatus: Color @Composable @ReadOnlyComposable get() = LocalTaskRxPalette.current.snoozedStatus
}

@Composable
fun TaskRxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Plain greys for the surfaces: Material's own dark surface has a purple cast that
    // fights the blue accent.
    val scheme =
        if (dark) {
            darkColorScheme(
                primary = DarkPalette.accent,
                onPrimary = Color.White,
                error = DarkPalette.error,
                background = Color(0xFF121212),
                onBackground = Color(0xFFE8E8E8),
                surface = Color(0xFF121212),
                onSurface = Color(0xFFE8E8E8),
                surfaceVariant = Color(0xFF242424),
                onSurfaceVariant = Color(0xFFC0C0C0),
                surfaceContainer = Color(0xFF1C1C1C),
                surfaceContainerHigh = Color(0xFF242424),
                outline = DarkPalette.outline,
            )
        } else {
            lightColorScheme(
                primary = LightPalette.accent,
                error = LightPalette.error,
            )
        }
    CompositionLocalProvider(LocalTaskRxPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = scheme) {
            // A base layer, so screens drawn outside a Scaffold (sign-in) get the right text colour.
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
