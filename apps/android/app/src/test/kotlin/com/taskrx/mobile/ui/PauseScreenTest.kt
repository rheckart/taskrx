package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.mobile.ui.reminders.ReminderEntry
import com.taskrx.mobile.ui.reminders.RemindersActions
import com.taskrx.mobile.ui.reminders.RemindersContent
import com.taskrx.mobile.ui.reminders.RemindersState
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val NOW = Instant.parse("2026-11-03T08:00:00Z").toEpochMilli()
private const val DAY = 86_400_000L

@Config(qualifiers = "w411dp-h2400dp")
@RunWith(RobolectricTestRunner::class)
class PauseScreenTest {
    @get:Rule val compose = createComposeRule()

    private val log = mutableListOf<String>()

    private fun med(kind: ReminderKind = ReminderKind.Medication): Reminder =
        createReminder("r", "u", kind, "Warfarin", NOW, NOW, schedule = if (kind == ReminderKind.Task) noneTimes() else dailyTimes(listOf("09:00")))

    private fun show(entry: ReminderEntry) {
        val actions =
            RemindersActions(
                onRefresh = {},
                onEdit = {},
                onStop = { log += "stop" },
                onPause = { r, c -> log += "pause:${r.name}:${c.from - NOW}:${c.until}" },
                onResume = { log += "resume:${it.name}" },
                onSearchDrug = {},
            )
        compose.setContent { TaskRxTheme { RemindersContent(RemindersState(entries = listOf(entry), loading = false), actions, now = { NOW }) } }
    }

    @Test
    fun `a medication can be paused, and the dialog starts from now until the person resumes`() {
        show(ReminderEntry(med(), null))

        compose.onNodeWithContentDescription("Pause Warfarin").performClick()
        compose.onNodeWithText("Pause Warfarin?").assertExists()
        compose.onNodeWithText("Now").assertExists()
        compose.onNodeWithText("When I resume it").assertExists()
        compose.onNode(hasText("Pause") and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()

        assertEquals(listOf("pause:Warfarin:0:null"), log)
        compose.onAllNodesWithText("Pause Warfarin?").assertCountEquals(0)
    }

    @Test
    fun `cancelling the dialog pauses nothing`() {
        show(ReminderEntry(med(), null))

        compose.onNodeWithContentDescription("Pause Warfarin").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList(), log)
        compose.onAllNodesWithText("Pause Warfarin?").assertCountEquals(0)
    }

    @Test
    fun `the date pickers open from the dialog and each can be put back`() {
        show(ReminderEntry(med(), null))
        compose.onNodeWithContentDescription("Pause Warfarin").performClick()

        compose.onNodeWithContentDescription("Pause from, Now").performClick()
        compose.onNodeWithText("Use now").performClick()
        compose.onNodeWithContentDescription("Start again on, When I resume it").performClick()
        compose.onNodeWithText("No date").performClick()
        compose.onNodeWithContentDescription("Start again on, When I resume it").performClick()
        compose.onNodeWithText("OK").assertExists()
    }

    @Test
    fun `a task has no Pause`() {
        show(ReminderEntry(med(ReminderKind.Task), null))
        compose.onNodeWithText("Tasks").performClick()

        compose.onNodeWithContentDescription("Edit Warfarin").assertExists()
        compose.onAllNodesWithText("Pause").assertCountEquals(0)
    }

    @Test
    fun `a running pause shows until when, and offers Resume instead of Pause`() {
        show(ReminderEntry(med(), null, PauseWindow(NOW - DAY, NOW + 3 * DAY)))

        compose.onNodeWithText("Paused until Fri, Nov 6").assertExists()
        compose.onAllNodesWithText("Pause").assertCountEquals(0)
        compose.onNodeWithContentDescription("Resume Warfarin").performClick()

        assertEquals(listOf("resume:Warfarin"), log)
    }

    @Test
    fun `a pause that has not started can be cancelled`() {
        show(ReminderEntry(med(), null, PauseWindow(NOW + 5 * DAY, NOW + 9 * DAY)))

        compose.onNodeWithText("Pause starts Sun, Nov 8 · back Thu, Nov 12").assertExists()
        compose.onNodeWithContentDescription("Cancel pause Warfarin").performClick()

        assertEquals(listOf("resume:Warfarin"), log)
    }
}
