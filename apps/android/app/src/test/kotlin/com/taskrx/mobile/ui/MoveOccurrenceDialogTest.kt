package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.mobile.ui.today.MoveDraft
import com.taskrx.mobile.ui.today.MoveOccurrenceDialog
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val MIDNIGHT = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli()
private val NOW = MIDNIGHT + 8 * 3_600_000L
private val SOURCE = MIDNIGHT + 12 * 3_600_000L

private fun task(schedule: com.taskrx.core.domain.ScheduleTimes = onceTimes(SOURCE)) =
    createReminder("task", "u", ReminderKind.Task, "Call pharmacy", MIDNIGHT - 86_400_000L, MIDNIGHT - 86_400_000L, schedule = schedule)

private fun row(at: Long = SOURCE, status: InstanceStatus = InstanceStatus.Pending, snoozedUntil: Long? = null) =
    TimelineRow(
        reminderId = "task",
        instanceId = null,
        name = "Call pharmacy",
        dosage = null,
        kind = ReminderKind.Task,
        scheduledTime = at,
        status = status,
        snoozedUntil = snoozedUntil,
        isNext = false,
    )

/**
 * The reschedule dialog. Which writes and refusals apply is `:core`'s rule (`planTaskReschedule`),
 * unit-tested there; what is pinned here is that the dialog shows presets and reasons and only lets
 * the person press Reschedule when allowed.
 */
@RunWith(RobolectricTestRunner::class)
class MoveOccurrenceDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        draft: MoveDraft,
        now: Long = NOW,
        snoozeMinutes: Int = 15,
        onConfirm: (Long) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent { MoveOccurrenceDialog(draft, now, onConfirm, onDismiss, snoozeMinutes) }
    }

    @Test
    fun `it names the task and both moments`() {
        show(MoveDraft(row(), task(), emptyList()))

        compose.onNodeWithText("Reschedule Call pharmacy").assertExists()
        compose.onNodeWithText("Now:", substring = true).assertExists()
        compose.onNodeWithText("Reschedules to", substring = true).assertExists()
    }

    @Test
    fun `a future task opens on its current moment, so confirming untouched is refused`() {
        show(MoveDraft(row(), task(), emptyList()))

        compose.onNodeWithText("Pick a moment different from the one it is on.").performScrollTo().assertExists()
        compose.onNodeWithText("Reschedule").assertIsNotEnabled()
    }

    @Test
    fun `a task that was already answered says so and offers no presets`() {
        val answered =
            createReminderInstance(
                id = "i1",
                reminderId = "task",
                userId = "u",
                reminderKind = ReminderKind.Task,
                scheduledTime = SOURCE,
                createdAt = NOW,
                updatedAt = NOW,
                status = InstanceStatus.Completed,
            )
        show(MoveDraft(row(), task(), listOf(answered)))

        compose.onNodeWithText("This one has already been answered, and an answer is not rewritten.").performScrollTo().assertExists()
        compose.onNodeWithText("Reschedule").assertIsNotEnabled()
        assertEquals(0, compose.onAllNodesWithText("Tomorrow").fetchSemanticsNodes().size)
    }

    @Test
    fun `an overdue task past two hours opens ready to reschedule with presets`() {
        val overdueAt = MIDNIGHT + 1 * 3_600_000L
        var chosen: Long? = null
        show(MoveDraft(row(overdueAt, InstanceStatus.Missed), task(onceTimes(overdueAt)), emptyList()), now = NOW, onConfirm = { chosen = it })

        compose.onNodeWithText("In 15 min").assertExists()
        compose.onNodeWithText("In 1 hour").assertExists()
        compose.onNodeWithText("Tomorrow").assertExists()
        compose.onNodeWithText("Reschedule").assertIsEnabled().performClick()

        // Default target for overdue is tomorrow at the same clock time.
        assertEquals(overdueAt + 24 * 3_600_000L, chosen)
    }

    @Test
    fun `tapping a quick preset confirms immediately`() {
        val overdueAt = MIDNIGHT + 1 * 3_600_000L
        var chosen: Long? = null
        show(MoveDraft(row(overdueAt), task(onceTimes(overdueAt)), emptyList()), now = NOW, snoozeMinutes = 30, onConfirm = { chosen = it })

        compose.onNodeWithText("In 30 min").performClick()

        assertEquals(NOW + 30 * 60_000L, chosen)
    }

    @Test
    fun `an occurrence with an open pending row can still be rescheduled`() {
        val pending =
            createReminderInstance(
                id = "i1",
                reminderId = "task",
                userId = "u",
                reminderKind = ReminderKind.Task,
                scheduledTime = SOURCE,
                createdAt = NOW,
                updatedAt = NOW,
            )
        var chosen: Long? = null
        show(MoveDraft(row(), task(), listOf(pending)), onConfirm = { chosen = it })

        compose.onNodeWithText("Tomorrow").performClick()

        assertEquals(SOURCE + 24 * 3_600_000L, chosen)
    }

    @Test
    fun `a medication is never rescheduled`() {
        val medication =
            createReminder("m", "u", ReminderKind.Medication, "Warfarin", MIDNIGHT, MIDNIGHT, schedule = dailyTimes(listOf("09:00")))
        show(MoveDraft(row(), medication, emptyList()))

        compose.onNodeWithText("Only a task can be rescheduled: a medication schedule is a clinical instruction.").performScrollTo().assertExists()
        compose.onNodeWithText("Reschedule").assertIsNotEnabled()
    }

    @Test
    fun `Cancel closes without rescheduling`() {
        var dismissed = false
        var moved: Long? = null
        show(MoveDraft(row(), task(), emptyList()), onConfirm = { moved = it }, onDismiss = { dismissed = true })

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(true, dismissed)
        assertNull(moved)
    }

    @Test
    fun `a task scheduled tomorrow offers a +1 day preset`() {
        val future = MIDNIGHT + 24 * 3_600_000L + 12 * 3_600_000L
        val reminder = task(onceTimes(future))
        var moved: Long? = null
        show(MoveDraft(row(future), reminder, emptyList()), now = NOW, onConfirm = { moved = it })

        compose.onNodeWithText("Reschedule").assertIsNotEnabled()
        compose.onNodeWithText("+1 day").performClick()

        assertEquals(future + 24 * 3_600_000L, moved)
    }

    @Test
    fun `an existing override does not hide the dialog's facts`() {
        val moved = task().copy(overrides = listOf(OccurrenceOverride(SOURCE, SOURCE + 3_600_000L)))
        show(MoveDraft(row(), moved, emptyList()))

        compose.onNodeWithText("Only this occurrence. The schedule stays as it is.").assertExists()
        compose.onNodeWithText("Reschedule").assertIsNotEnabled()
    }
}
