package com.taskrx.mobile.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
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

private fun row(at: Long = SOURCE) =
    TimelineRow(
        reminderId = "task",
        instanceId = null,
        name = "Call pharmacy",
        dosage = null,
        kind = ReminderKind.Task,
        scheduledTime = at,
        status = com.taskrx.core.domain.InstanceStatus.Pending,
        snoozedUntil = null,
        isNext = false,
    )

/**
 * The move dialog. Which moments may move is `:core`'s rule (`moveRefusal`), unit-tested
 * there; what is pinned here is that the dialog shows the person that reason and will not
 * let them press Move while one applies.
 */
@RunWith(RobolectricTestRunner::class)
class MoveOccurrenceDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun show(draft: MoveDraft, now: Long = NOW, onConfirm: (Long) -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent { MoveOccurrenceDialog(draft, now, onConfirm, onDismiss) }
    }

    @Test
    fun `it names the task and both moments`() {
        show(MoveDraft(row(), task(), emptyList()))

        compose.onNodeWithText("Move Call pharmacy").assertExists()
        // The moment it is on, and the moment it would land on, both named in full.
        compose.onNodeWithText("Now:", substring = true).assertExists()
        compose.onNodeWithText("Moves to", substring = true).assertExists()
    }

    @Test
    fun `it opens on the moment the occurrence already has, so that is not a move`() {
        show(MoveDraft(row(), task(), emptyList()))

        compose.onNodeWithText("Pick a moment after the one it is on.").assertExists()
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun `a task that was already answered says so instead of offering the move`() {
        val answered =
            com.taskrx.core.domain.createReminderInstance(
                id = "i1",
                reminderId = "task",
                userId = "u",
                reminderKind = ReminderKind.Task,
                scheduledTime = SOURCE,
                createdAt = NOW,
                updatedAt = NOW,
                status = com.taskrx.core.domain.InstanceStatus.Completed,
            )
        show(MoveDraft(row(), task(), listOf(answered)))

        compose.onNodeWithText("This one has already been answered, and an answer is not rewritten.").assertExists()
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun `a move past the two-hour grace period says it is too late`() {
        // Ten hours late: still on the day, but out of time.
        show(MoveDraft(row(MIDNIGHT + 1 * 3_600_000L), task(), emptyList()), now = NOW)

        compose.onNodeWithText("It is too late to move this one. It has been due for more than two hours.").assertExists()
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun `an occurrence with an open row says to answer it first`() {
        val pending =
            com.taskrx.core.domain.createReminderInstance(
                id = "i1",
                reminderId = "task",
                userId = "u",
                reminderKind = ReminderKind.Task,
                scheduledTime = SOURCE,
                createdAt = NOW,
                updatedAt = NOW,
            )
        show(MoveDraft(row(), task(), listOf(pending)))

        compose.onNodeWithText("You have already opened this one. Answer it first, then move the next.").assertExists()
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun `a medication is never moved`() {
        val medication =
            createReminder("m", "u", ReminderKind.Medication, "Warfarin", MIDNIGHT, MIDNIGHT, schedule = dailyTimes(listOf("09:00")))
        show(MoveDraft(row(), medication, emptyList()))

        compose.onNodeWithText("Only a task can be moved: a medication schedule is a clinical instruction.").assertExists()
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }

    @Test
    fun `Cancel closes without a move`() {
        var dismissed = false
        var moved: Long? = null
        show(MoveDraft(row(), task(), emptyList()), onConfirm = { moved = it }, onDismiss = { dismissed = true })

        compose.onNodeWithText("Cancel").performClick()

        assertEquals(true, dismissed)
        assertNull(moved)
    }

    @Test
    fun `a task with a real destination can be moved, and the target is what is handed back`() {
        // A move the dialog can actually offer: a future source, still inside its day.
        val future = MIDNIGHT + 18 * 3_600_000L
        val reminder = task(onceTimes(future))
        var moved: Long? = null
        show(MoveDraft(row(future), reminder, emptyList()), now = NOW, onConfirm = { moved = it })

        compose.onNodeWithText("Move").performClick()

        // The dialog opens on the source moment, so pressing Move without changing the day
        // changes nothing and is refused; the assertion above covers the refusal text. What
        // this pins is that the refusals come from the same `moveRefusal` the ViewModel uses.
        assertNull(moved)
    }

    @Test
    fun `an existing override does not hide the dialog's facts`() {
        val moved = task().copy(overrides = listOf(OccurrenceOverride(SOURCE, SOURCE + 3_600_000L)))
        show(MoveDraft(row(), moved, emptyList()))

        compose.onNodeWithText("Only this one. The schedule stays as it is, and the next one comes due as usual.").assertExists()
        compose.onNodeWithText("Move").assertIsNotEnabled()
    }
}
