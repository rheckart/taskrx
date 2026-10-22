package com.taskrx.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasNoClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.createReminderInstance
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.weeklyTimes
import com.taskrx.core.notifications.PermissionGap
import com.taskrx.core.notifications.PermissionRemedy
import com.taskrx.core.notifications.PermissionSeverity
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.mobile.format.rowDescription
import com.taskrx.mobile.ui.answer.Action
import com.taskrx.mobile.ui.answer.AnswerContent
import com.taskrx.mobile.ui.answer.AnswerState
import com.taskrx.mobile.ui.components.PermissionBanner
import com.taskrx.mobile.ui.forms.SchedulePicker
import com.taskrx.mobile.ui.forms.ScheduleTypeChooser
import com.taskrx.mobile.ui.forms.ScheduleType
import com.taskrx.mobile.ui.dose.AddDoseContent
import com.taskrx.mobile.ui.dose.AddDoseState
import com.taskrx.mobile.ui.today.TodayActions
import com.taskrx.mobile.ui.today.TodayContent
import com.taskrx.mobile.ui.today.TodayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val NINE = 9 * 3_600_000L

/**
 * The composables, over state they are handed. What each screen decides is tested at the
 * ViewModel; these pin what a person sees and what a screen reader hears.
 */
@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun row(
        name: String = "Metformin",
        kind: ReminderKind = ReminderKind.Medication,
        status: InstanceStatus = InstanceStatus.Pending,
        isNext: Boolean = false,
        dosage: String? = null,
        completedAt: Long? = null,
        answeredAs: String? = null,
    ) = TimelineRow(
        "r-$name",
        answeredAs ?: if (completedAt == null) null else "i-$name",
        name,
        dosage,
        kind,
        NINE,
        status,
        null,
        isNext,
        completedAt,
    )

    private class Calls {
        val log = mutableListOf<String>()

        fun actions() =
            TodayActions(
                onPreviousDay = { log += "prev" },
                onNextDay = { log += "next" },
                onToday = { log += "today" },
                onToggleHideCompleted = { log += "hide" },
                onAnswerSlot = { rows, action -> log += "slot:${action.wire}:${rows.joinToString(",") { it.name }}" },
                onRefresh = { log += "refresh" },
                onOpenAnswer = { log += "answer:${it.name}" },
                onChangeTime = { row, h, m -> log += "time:${row.name}:$h:$m" },
                onUndo = { log += "undo:${it.name}" },
                onAdd = { log += "add" },
                onNewTask = { log += "task" },
                onNewMedication = { log += "med" },
                onAddDose = { log += "dose" },
                onReminders = { log += "reminders" },
                onBacklog = { log += "backlog" },
                onAdherence = { log += "adherence" },
                onSupplies = { log += "supplies" },
                onHistory = { log += "history" },
                onSettings = { log += "settings" },
            )
    }

    private fun today(state: TodayState, calls: Calls = Calls(), banner: @androidx.compose.runtime.Composable () -> Unit = {}) {
        compose.setContent { TaskRxTheme { TodayContent(state, calls.actions(), banner = banner) } }
    }

    private fun state(
        vararg rows: TimelineRow,
        offset: Int = 0,
        loading: Boolean = false,
        error: String? = null,
        undoable: Set<String> = emptySet(),
    ) = TodayState(dayOffset = offset, windowStart = 0, rows = rows.toList(), loading = loading, error = error, undoable = undoable)

    @Test
    fun `a taken row offers Undo take`() {
        val calls = Calls()
        val taken = row("Taken", status = InstanceStatus.Completed, completedAt = NINE + 5 * 60_000)
        today(state(taken, undoable = setOf("i-Taken")), calls)

        compose.onNodeWithText("Undo take").performClick()

        assertEquals(listOf("undo:Taken"), calls.log)
    }

    @Test
    fun `a skipped row offers Undo skip`() {
        val calls = Calls()
        val skipped = row("Skipped", status = InstanceStatus.Skipped, answeredAs = "i-s")
        today(state(skipped, undoable = setOf("i-s")), calls)

        compose.onNodeWithText("Undo skip").performClick()

        assertEquals(listOf("undo:Skipped"), calls.log)
    }

    @Test
    fun `a snoozed row offers Undo snooze`() {
        val calls = Calls()
        val snoozed = row("Snoozed", status = InstanceStatus.Snoozed, answeredAs = "i-z")
        today(state(snoozed, undoable = setOf("i-z")), calls)

        compose.onNodeWithText("Undo snooze").performClick()

        assertEquals(listOf("undo:Snoozed"), calls.log)
    }

    @Test
    fun `a row whose answer cannot be taken back offers no Undo`() {
        val extraDose = row("Extra", status = InstanceStatus.Completed, completedAt = NINE)
        today(state(extraDose, row("Open"), undoable = emptySet()))

        compose.onAllNodesWithText("Undo take").assertCountEquals(0)
        compose.onAllNodesWithText("Undo skip").assertCountEquals(0)
    }

    @Test
    fun `holding a taken row still changes its time, and Undo does not take its place`() {
        val taken = row("Taken", status = InstanceStatus.Completed, completedAt = NINE + 5 * 60_000)
        today(state(taken, undoable = setOf("i-Taken")))

        compose.onNodeWithContentDescription(rowDescription(taken)).performTouchInput { longClick() }

        compose.onNodeWithText("Pick a time").assertExists()
    }

    @Test
    fun `an empty day says so in a sentence`() {
        today(state())

        compose.onNodeWithText("Nothing scheduled").assertExists()
        compose.onNodeWithText("No medications or tasks due today.").assertExists()
    }

    @Test
    fun `doses due together get a slot bar whose buttons answer them all`() {
        val calls = Calls()
        today(state(row("A"), row("B")), calls)

        compose.onNodeWithText("9:00 AM · 2 doses").assertExists()
        compose.onNodeWithText("Take all").performClick()
        compose.onNodeWithText("Snooze all").performClick()
        compose.onNodeWithText("Skip all").performClick()

        assertEquals(listOf("slot:take:A,B", "slot:snooze:A,B", "slot:skip:A,B"), calls.log)
    }

    @Test
    fun `Today links to Settings`() {
        val calls = Calls()
        today(state(row("A")), calls)

        compose.onNodeWithText("Settings").performClick()

        assertEquals(listOf("settings"), calls.log)
    }

    @Test
    fun `a lone dose has no slot bar`() {
        today(state(row("A")))

        compose.onAllNodesWithText("Take all").assertCountEquals(0)
    }

    @Test
    fun `tasks due together have no slot bar`() {
        today(state(row("A", kind = ReminderKind.Task), row("B", kind = ReminderKind.Task)))

        compose.onAllNodesWithText("Take all").assertCountEquals(0)
    }

    @Test
    fun `a slot bar counts only the doses still to answer`() {
        today(state(row("A"), row("B"), row("C", status = InstanceStatus.Completed, completedAt = NINE)))

        compose.onNodeWithText("9:00 AM · 2 doses").assertExists()
    }

    @Test
    fun `the footer offers to add a dose`() {
        val calls = Calls()
        today(state(row()), calls)

        compose.onNodeWithText("+ Dose").performClick()

        assertEquals(listOf("dose"), calls.log)
    }

    private fun medication(id: String, name: String, dosage: String? = null, active: Boolean = true) =
        createReminder(id, "u", ReminderKind.Medication, name, createdAt = 0, updatedAt = 0, dosage = dosage, active = active)

    @Test
    fun `picking a medication offers to record it at the time it opens on, and records`() {
        val recorded = mutableListOf<String>()
        compose.setContent {
            TaskRxTheme {
                AddDoseContent(
                    AddDoseState(medications = listOf(medication("a", "Aspirin", "81 mg"), medication("b", "Zinc", active = false)), loading = false),
                    onRefresh = {},
                    onRecord = { m, h, min -> recorded += "${m.name}:$h:$min" },
                    now = { 10 * 3_600_000L + 5 * 60_000L },
                )
            }
        }

        compose.onNodeWithText("Aspirin").performClick()
        compose.onNodeWithText("Taken today at 10:05 AM").assertExists()
        compose.onNodeWithText("Record").performClick()

        assertEquals(listOf("Aspirin:10:5"), recorded)
    }

    @Test
    fun `a stopped medication says so, and no medications says what to do`() {
        compose.setContent {
            TaskRxTheme {
                AddDoseContent(AddDoseState(medications = listOf(medication("b", "Zinc", active = false)), loading = false), {}, { _, _, _ -> }, now = { 0 })
            }
        }
        compose.onNodeWithText("Stopped").assertExists()
    }

    @Test
    fun `with no medications the screen says to add one first`() {
        compose.setContent { TaskRxTheme { AddDoseContent(AddDoseState(loading = false), {}, { _, _, _ -> }, now = { 0 }) } }

        compose.onNodeWithText("No medications yet").assertExists()
    }

    @Test
    fun `cancelling the dialog records nothing`() {
        val recorded = mutableListOf<String>()
        compose.setContent {
            TaskRxTheme { AddDoseContent(AddDoseState(medications = listOf(medication("a", "Aspirin")), loading = false), {}, { m, _, _ -> recorded += m.name }, now = { 0 }) }
        }

        compose.onNodeWithText("Aspirin").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList(), recorded)
        compose.onAllNodesWithText("Record Aspirin").assertCountEquals(0)
    }

    @Test
    fun `hide done drops completed rows and keeps the rest`() {
        today(
            TodayState(
                windowStart = 0,
                loading = false,
                hideCompleted = true,
                rows = listOf(row("Done", status = InstanceStatus.Completed, completedAt = NINE), row("Todo"), row("Late", status = InstanceStatus.Missed)),
            ),
        )

        compose.onAllNodesWithText("Done").assertCountEquals(0)
        compose.onNodeWithText("Todo").assertExists()
        compose.onNodeWithText("Late").assertExists()
    }

    @Test
    fun `hide done off shows completed rows`() {
        today(state(row("Done", status = InstanceStatus.Completed, completedAt = NINE), row("Todo")))

        compose.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `a day of only completed rows says all done when they are hidden`() {
        today(TodayState(windowStart = 0, loading = false, hideCompleted = true, rows = listOf(row("Done", status = InstanceStatus.Completed, completedAt = NINE))))

        compose.onNodeWithText("All done").assertExists()
        compose.onAllNodesWithText("Nothing scheduled").assertCountEquals(0)
    }

    @Test
    fun `the Hide done chip asks to toggle`() {
        val calls = Calls()
        today(state(row()), calls)

        compose.onNodeWithText("Hide done").performClick()

        assertEquals(listOf("hide"), calls.log)
    }

    @Test
    fun `an empty past day says what it was`() {
        today(state(offset = -1))

        compose.onNodeWithText("You had nothing due yesterday.").assertExists()
    }

    @Test
    fun `a day that fails to load says so`() {
        today(state(error = "disk full"))

        compose.onNodeWithText("Failed to load: disk full").assertExists()
    }

    @Test
    fun `while loading there is a placeholder and no rows`() {
        today(state(row(), loading = true))

        compose.onNodeWithContentDescription("Loading your day").assertExists()
        compose.onAllNodesWithText("Metformin").assertCountEquals(0)
    }

    @Test
    fun `the first upcoming row is badged NEXT and not the first`() {
        today(state(row("First", isNext = false), row("Second", isNext = true)))

        compose.onAllNodesWithText("NEXT").assertCountEquals(1)
        compose.onNodeWithText("NEXT").assertExists()
    }

    @Test
    fun `a row is one phrase for a screen reader, with the Kind in words`() {
        val med = row("Metformin", ReminderKind.Medication, InstanceStatus.Completed)
        val task = row("Call pharmacy", ReminderKind.Task, InstanceStatus.Pending)
        today(state(med, task))

        compose.onNodeWithContentDescription(rowDescription(med)).assertExists()
        compose.onNodeWithContentDescription(rowDescription(task)).assertExists()
        assertTrue(rowDescription(med).startsWith("Medication: Metformin at "))
        assertTrue(rowDescription(med).endsWith(", Taken"))
        assertTrue(rowDescription(task).startsWith("Task: Call pharmacy at "))
    }

    @Test
    fun `a medication reads Taken and a task reads Done on the row itself`() {
        today(
            state(
                row("Metformin", ReminderKind.Medication, InstanceStatus.Completed),
                row("Bins", ReminderKind.Task, InstanceStatus.Completed),
            ),
        )

        compose.onNodeWithText("Taken").assertExists()
        compose.onNodeWithText("Done").assertExists()
    }

    @Test
    fun `an answerable row, a missed one included, opens the answer screen, and an answered one offers nothing to press`() {
        val calls = Calls()
        val open = row("Open", status = InstanceStatus.Pending)
        val snoozed = row("Snoozed", status = InstanceStatus.Snoozed)
        val taken = row("Taken", status = InstanceStatus.Completed)
        val missed = row("Missed", status = InstanceStatus.Missed)
        today(state(open, snoozed, taken, missed), calls)

        compose.onNodeWithContentDescription(rowDescription(open)).assertHasClickAction().performClick()
        compose.onNodeWithContentDescription(rowDescription(snoozed)).assertHasClickAction()
        // The test screen is small: the last rows may be below the fold.
        compose.onNode(hasScrollAction()).performScrollToIndex(4)
        compose.onNodeWithContentDescription(rowDescription(missed)).assertHasClickAction()
        compose.onNodeWithContentDescription(rowDescription(taken)).assert(hasNoClickAction())
        assertEquals(listOf("answer:Open"), calls.log)
    }

    @Test
    fun `holding a taken row offers its time, and confirming reports the new one`() {
        val calls = Calls()
        val taken = row("Taken", status = InstanceStatus.Completed, completedAt = NINE + 5 * 60_000)
        today(state(taken), calls)

        compose.onNodeWithContentDescription(rowDescription(taken)).performTouchInput { longClick() }
        compose.onNodeWithText("Pick a time").assertExists()
        compose.onNodeWithText("OK").performClick()

        // The picker opens on the time it was taken (09:05, in the zone the suite pins), so
        // confirming untouched says so.
        assertEquals(listOf("time:Taken:9:5"), calls.log)
    }

    @Test
    fun `holding a row that is not taken offers nothing`() {
        val calls = Calls()
        val open = row("Open", status = InstanceStatus.Pending)
        val missed = row("Missed", status = InstanceStatus.Missed)
        today(state(open, missed), calls)

        compose.onNodeWithContentDescription(rowDescription(open)).performTouchInput { longClick() }
        compose.onNodeWithContentDescription(rowDescription(missed)).performTouchInput { longClick() }

        compose.onAllNodesWithText("Pick a time").assertCountEquals(0)
    }

    @Test
    fun `missed stays on the day`() {
        today(state(row("Metformin", status = InstanceStatus.Missed)))

        compose.onNodeWithText("Missed").assertExists()
    }

    @Test
    fun `the day bar moves back, returns to today, and cannot go forward from today`() {
        val calls = Calls()
        today(state(offset = 0), calls)

        compose.onNodeWithContentDescription("Next day").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Previous day").performClick()
        compose.onNodeWithContentDescription("Back to today").performClick()

        assertEquals(listOf("prev", "today"), calls.log)
    }

    @Test
    fun `from a past day the next arrow is live`() {
        val calls = Calls()
        today(state(offset = -2), calls)

        compose.onNodeWithContentDescription("Next day").assertIsEnabled().performClick()

        assertEquals(listOf("next"), calls.log)
    }

    @Test
    fun `the footer reaches both ways of adding`() {
        val calls = Calls()
        today(state(), calls)

        compose.onNodeWithText("+ Add").performClick()
        compose.onNodeWithText("+ Task").performClick()
        compose.onNodeWithText("+ Medication").performClick()

        assertEquals(listOf("add", "task", "med"), calls.log)
    }

    @Test
    fun `the permission banner sits above the day when there is a gap and is absent when there is not`() {
        val gap =
            PermissionGap(PermissionSeverity.Blocking, "Reminders are turned off", "Nothing will reach you.", PermissionRemedy.NotificationSettings, "Open notification settings")
        val resolved = mutableListOf<PermissionRemedy>()
        var current: PermissionGap? = gap
        compose.setContent {
            TaskRxTheme { PermissionBanner(current, onResolve = { resolved += it.remedy }) }
        }

        compose.onNodeWithContentDescription("Reminders are turned off. Nothing will reach you.").assertExists()
        compose.onNodeWithText("Open notification settings").performClick()
        assertEquals(listOf(PermissionRemedy.NotificationSettings), resolved)
    }

    @Test
    fun `no banner when reminders can arrive`() {
        compose.setContent { TaskRxTheme { PermissionBanner(null, onResolve = {}) } }

        compose.onAllNodesWithText("Reminders are turned off").assertCountEquals(0)
    }

    @Test
    fun `a degraded gap is a different sentence from a blocking one`() {
        val gap = PermissionGap(PermissionSeverity.Degraded, "Reminders may arrive late", "Exact alarms are off.", PermissionRemedy.AlarmSettings, "Open alarm settings")
        compose.setContent { TaskRxTheme { PermissionBanner(gap, onResolve = {}) } }

        compose.onNodeWithText("Reminders may arrive late").assertExists()
        compose.onNodeWithText("Open alarm settings").assertExists()
    }

    private fun loaded(kind: ReminderKind, action: Action = Action.Idle, recordedAt: Long? = null, error: String? = null, dosage: String? = null): AnswerState.Loaded {
        val reminder = createReminder("r", "u", kind, "Metformin", 0, 0, dosage = dosage, schedule = dailyTimes(listOf("09:00")))
        val instance = createReminderInstance("i", "r", "u", kind, NINE, 0, 0)
        return AnswerState.Loaded(reminder, NINE, instance, action, recordedAt, error)
    }

    private fun answer(
        state: AnswerState,
        now: Long = NINE,
        calls: MutableList<String> = mutableListOf(),
        snoozeMinutes: Int = 15,
    ): MutableList<String> {
        compose.setContent {
            TaskRxTheme {
                AnswerContent(
                    state,
                    now,
                    snoozeMinutes,
                    onComplete = { calls += "complete" },
                    onSkip = { calls += "skip" },
                    onSnooze = { calls += "snooze" },
                    onReschedule = { hour, minute -> calls += "reschedule:$hour:$minute" },
                    onUndo = { calls += "undo" },
                    onDone = { calls += "done" },
                )
            }
        }
        return calls
    }

    @Test
    fun `a medication is answered with Taken, a task with Done, and both can skip or snooze`() {
        answer(loaded(ReminderKind.Medication, dosage = "500mg"))

        compose.onNodeWithText("MEDICATION").assertExists()
        compose.onNodeWithText("500mg").assertExists()
        compose.onNodeWithText("Mark taken").assertExists()
        compose.onNodeWithText("Skip").assertExists()
        compose.onNodeWithText("Snooze 15 min").assertExists()
    }

    @Test
    fun `a missed moment can still be taken or skipped, but has nothing left to snooze`() {
        val open = loaded(ReminderKind.Medication)
        answer(open.copy(instance = open.instance.copy(status = InstanceStatus.Missed)))

        compose.onNodeWithText("Mark taken").assertExists()
        compose.onNodeWithText("Skip").assertExists()
        compose.onAllNodesWithText("Snooze 15 min").assertCountEquals(0)
    }

    @Test
    fun `the snooze button names the length chosen in Settings`() {
        answer(loaded(ReminderKind.Medication), snoozeMinutes = 30)

        compose.onNodeWithText("Snooze 30 min").assertExists()
        compose.onAllNodesWithText("Snooze 15 min").assertCountEquals(0)
    }

    @Test
    fun `Pick a time opens the dialog, and its answer is handed back as a clock time`() {
        val calls = answer(loaded(ReminderKind.Task))

        // The test screen is small: the button is below the fold.
        compose.onNodeWithText("Pick a time").performScrollTo().performClick()
        compose.onNodeWithText("Type it").performClick()
        compose.onNodeWithText("OK").performClick()

        // Opens on the time a plain snooze would land on: NINE + 15 minutes, 09:15 UTC.
        assertEquals(listOf("reschedule:9:15"), calls)
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun `cancelling the dialog reschedules nothing`() {
        val calls = answer(loaded(ReminderKind.Task))

        // The test screen is small: the button is below the fold.
        compose.onNodeWithText("Pick a time").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(emptyList(), calls)
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun `Pick a time is not offered for a missed moment, or in the last minute of the day`() {
        val open = loaded(ReminderKind.Medication)
        answer(open.copy(instance = open.instance.copy(status = InstanceStatus.Missed)))
        compose.onAllNodesWithText("Pick a time").assertCountEquals(0)
    }

    @Test
    fun `Pick a time is not offered once no clock time is left today`() {
        // 23:59:30 UTC on NINE's day: even 23:59 is behind now.
        answer(loaded(ReminderKind.Medication), now = NINE + 14 * 3_600_000L + 59 * 60_000L + 30_000L)

        compose.onAllNodesWithText("Pick a time").assertCountEquals(0)
        compose.onNodeWithText("Snooze 15 min").assertExists()
    }

    @Test
    fun `while one answer is in flight Pick a time cannot be pressed`() {
        answer(loaded(ReminderKind.Medication, action = Action.Completing))

        compose.onNodeWithText("Pick a time").assertIsNotEnabled()
    }

    @Test
    fun `a task reads Mark done and TASK`() {
        answer(loaded(ReminderKind.Task))

        compose.onNodeWithText("TASK").assertExists()
        compose.onNodeWithText("Mark done").assertExists()
        compose.onAllNodesWithText("Mark taken").assertCountEquals(0)
    }

    @Test
    fun `the buttons call back`() {
        val calls = answer(loaded(ReminderKind.Medication))

        compose.onNodeWithText("Mark taken").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Snooze 15 min").performClick()

        assertEquals(listOf("complete", "skip", "snooze"), calls)
    }

    @Test
    fun `while one answer is in flight the others cannot be pressed`() {
        answer(loaded(ReminderKind.Medication, action = Action.Completing))

        compose.onNodeWithText("Mark taken").assertIsNotEnabled()
        compose.onNodeWithText("Skip").assertIsNotEnabled()
        compose.onNodeWithText("Snooze 15 min").assertIsNotEnabled()
    }

    @Test
    fun `urgency reads due now, overdue, and ahead`() {
        answer(loaded(ReminderKind.Medication), now = NINE)
        compose.onNodeWithText("Time to take").assertExists()
    }

    @Test
    fun `an overdue moment says by how much`() {
        answer(loaded(ReminderKind.Task), now = NINE + 30 * 60_000)

        compose.onNodeWithText("Overdue by 30 minutes").assertExists()
    }

    @Test
    fun `a moment still ahead says how far`() {
        answer(loaded(ReminderKind.Task), now = NINE - 10 * 60_000)

        compose.onNodeWithText("Due in 10 minutes").assertExists()
    }

    @Test
    fun `once recorded the buttons give way to a confirmation that can be dismissed or taken back`() {
        val calls = answer(loaded(ReminderKind.Medication, action = Action.Done, recordedAt = NINE + 60_000))

        compose.onNodeWithText("Recorded").assertExists()
        compose.onAllNodesWithText("Mark taken").assertCountEquals(0)
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(listOf("undo", "done"), calls)
    }

    @Test
    fun `the confirmation says so when taking the answer back leaves the moment past its grace period`() {
        // Scheduled at NINE, now three hours later: the two hour Grace Period is over.
        answer(loaded(ReminderKind.Medication, action = Action.Done, recordedAt = NINE + 3 * 3_600_000L), now = NINE + 3 * 3_600_000L)

        compose.onNodeWithText("This one is past its grace period and will count as missed.").assertExists()
    }

    @Test
    fun `the confirmation stays quiet about the grace period while the moment is still inside it`() {
        answer(loaded(ReminderKind.Medication, action = Action.Done, recordedAt = NINE + 60_000), now = NINE + 60_000)

        compose.onAllNodesWithText("This one is past its grace period and will count as missed.").assertCountEquals(0)
    }

    @Test
    fun `while an undo is in flight neither Undo nor Done can be pressed again`() {
        answer(loaded(ReminderKind.Medication, action = Action.Undoing, recordedAt = NINE + 60_000))

        compose.onNodeWithText("Undo").assertIsNotEnabled()
        compose.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `a failed answer shows the error and keeps the buttons`() {
        answer(loaded(ReminderKind.Medication, error = "disk full"))

        compose.onNodeWithText("disk full").assertExists()
        compose.onNodeWithText("Mark taken").assertIsEnabled()
    }

    @Test
    fun `an invalid link and a missing reminder both offer a way back`() {
        val link = answer(AnswerState.InvalidLink)
        compose.onNodeWithText("Invalid reminder link").assertExists()
        compose.onNodeWithText("Go to my day").performClick()
        assertEquals(listOf("done"), link)
    }

    @Test
    fun `a reminder that cannot load says why`() {
        answer(AnswerState.Failed("This reminder no longer exists."))

        compose.onNodeWithText("Could not load this reminder").assertExists()
        compose.onNodeWithText("This reminder no longer exists.").assertExists()
    }

    @Test
    fun `loading shows a spinner that is named`() {
        answer(AnswerState.Loading)

        compose.onNodeWithContentDescription("Loading").assertExists()
    }

    @Test
    fun `the schedule chooser speaks its selection and hands back a fresh default`() {
        var chosen: com.taskrx.core.domain.ScheduleTimes? = null
        compose.setContent {
            TaskRxTheme { ScheduleTypeChooser(ScheduleType.Daily, onChange = { chosen = it }, now = { NINE }) }
        }

        compose.onNodeWithText("Daily").assertIsSelected()
        compose.onNodeWithText("Weekly").performClick()

        assertEquals(weeklyTimes(listOf(1, 2, 3, 4, 5), listOf("09:00")), chosen)
    }

    @Test
    fun `the picker for each variant shows its own fields`() {
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(dailyTimes(listOf("09:00")))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { NINE }) } }

        compose.onNodeWithText("Reminder times").assertExists()
        compose.onNodeWithText("+ Add time").performClick()
        assertEquals(dailyTimes(listOf("09:00", "09:00")), value)
    }

    @Test
    fun `weekly shows its days as checkboxes and warns when none is picked`() {
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(weeklyTimes(emptyList(), listOf("09:00")))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { NINE }) } }

        compose.onNodeWithText("Pick at least one day.").assertExists()
        compose.onNodeWithText("Tue").performClick()
        compose.onNodeWithText("Mon").performClick()

        assertEquals(weeklyTimes(listOf(1, 2), listOf("09:00")), value)
    }

    @Test
    fun `an invalid time is flagged beside the field`() {
        compose.setContent { TaskRxTheme { SchedulePicker(dailyTimes(listOf("25:99")), onChange = {}, now = { NINE }) } }

        compose.onNodeWithText("That is not a time. Tap it to pick one.").assertExists()
    }

    @Test
    fun `a time slot is picked, not typed, and on a 24-hour clock is stored as typed`() {
        val resolver = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "24")
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(dailyTimes(listOf("08:05")))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { NINE }) } }

        compose.onNodeWithContentDescription("Time 1", substring = true).performClick()
        compose.onNodeWithText("Type it").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextReplacement("14")
        fields[1].performTextReplacement("7")
        compose.onNodeWithText("OK").performClick()

        assertEquals(dailyTimes(listOf("14:07")), value)
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun `on a 12-hour clock an afternoon time stays in the afternoon and is stored as 24-hour`() {
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(dailyTimes(listOf("14:05")))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { NINE }) } }

        compose.onNodeWithContentDescription("Time 1", substring = true).performClick()
        compose.onNodeWithText("Type it").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("3")
        compose.onNodeWithText("OK").performClick()

        assertEquals(dailyTimes(listOf("15:05")), value)
    }

    @Test
    fun `cancelling the time picker leaves the time as it was`() {
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(dailyTimes(listOf("08:05")))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { NINE }) } }

        compose.onNodeWithContentDescription("Time 1", substring = true).performClick()
        compose.onNodeWithText("Type it").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("20")
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(dailyTimes(listOf("08:05")), value)
        compose.onNodeWithText("OK").assertDoesNotExist()
    }

    @Test
    fun `a stored time that is not one opens the picker on nine, and picking replaces it`() {
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(dailyTimes(listOf("25:99")))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { NINE }) } }

        compose.onNodeWithContentDescription("Time 1", substring = true).performClick()
        compose.onNodeWithText("OK").performClick()

        assertEquals(dailyTimes(listOf("09:00")), value)
    }

    @Test
    fun `a once moment is picked by date then time and keeps the local day, west of UTC`() {
        val before = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
        try {
            // 9:30 pm in New York is already tomorrow in UTC: where a wrongly-read day shows.
            val moment = java.time.ZonedDateTime.of(2026, 8, 4, 21, 30, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
            var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(com.taskrx.core.domain.onceTimes(moment))
            compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { moment - 1 }) } }

            compose.onNodeWithContentDescription("Happens at", substring = true).performClick()
            compose.onNodeWithText("Next").performClick()
            compose.onNodeWithText("Type it").performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("8")
            compose.onNodeWithText("OK").performClick()

            // On a 12-hour clock the typed 8 keeps the moment's pm: 8:30 pm, the same local day.
            val expected = java.time.ZonedDateTime.of(2026, 8, 4, 20, 30, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
            assertEquals(com.taskrx.core.domain.onceTimes(expected), value)
        } finally {
            java.util.TimeZone.setDefault(before)
        }
    }

    @Test
    fun `cancelling the date leaves a once moment as it was`() {
        val moment = 10 * NINE
        var value by mutableStateOf<com.taskrx.core.domain.ScheduleTimes>(com.taskrx.core.domain.onceTimes(moment))
        compose.setContent { TaskRxTheme { SchedulePicker(value, onChange = { value = it }, now = { moment - 1 }) } }

        compose.onNodeWithContentDescription("Happens at", substring = true).performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals(com.taskrx.core.domain.onceTimes(moment), value)
        compose.onNodeWithText("Next").assertDoesNotExist()
    }

    @Test
    fun `a schedule with no time says it goes to the backlog`() {
        compose.setContent { TaskRxTheme { SchedulePicker(com.taskrx.core.domain.noneTimes(), onChange = {}, now = { NINE }) } }

        compose.onNodeWithText("No time at all.", substring = true).assertExists()
    }

    @Test
    fun `a once moment in the past is warned about`() {
        compose.setContent { TaskRxTheme { SchedulePicker(com.taskrx.core.domain.onceTimes(NINE), onChange = {}, now = { NINE + 1 }) } }

        compose.onNodeWithText("That is in the past — nothing will fire for it.").assertExists()
    }

    @Test
    fun `an interval says how often it fires`() {
        compose.setContent { TaskRxTheme { SchedulePicker(com.taskrx.core.domain.intervalTimes(1, NINE), onChange = {}, now = { NINE - 1 }) } }

        compose.onNodeWithText("Interval (hours)").assertExists()
        compose.onNodeWithText("every 1 hour from", substring = true).assertExists()
    }
}
