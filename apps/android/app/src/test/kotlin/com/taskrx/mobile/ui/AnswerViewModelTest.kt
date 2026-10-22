package com.taskrx.mobile.ui

import android.app.AlarmManager
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.answer.Action
import com.taskrx.mobile.ui.answer.AnswerState
import com.taskrx.mobile.ui.answer.AnswerViewModel
import com.taskrx.mobile.ui.components.ToastKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private val NOW = Instant.parse("2026-08-06T09:05:00Z").toEpochMilli()
private val NINE = Instant.parse("2026-08-06T09:00:00Z").toEpochMilli()

/**
 * The answer screen as a person uses it: Completed, Skipped or Snoozed, for either Kind,
 * reached from a notification tap or a timeline tap. Storage, AlarmManager and WorkManager
 * are real underneath, so "after any write, reschedule" is observed rather than assumed.
 */
@RunWith(RobolectricTestRunner::class)
class AnswerViewModelTest {
    private val toasts = ToastLog()

    private fun Fixture.vm() = AnswerViewModel(container, toasts::add)

    private fun Fixture.alarms() = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>().getSystemService(AlarmManager::class.java)).scheduledAlarms

    private suspend fun Fixture.stored() = container.instances.list(InstanceFilter(USER))

    @Test
    fun `opening a moment mints the Instance the buttons act on, off the Reminder's own Kind`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            reminder("chore", ReminderKind.Task, onceTimes(NINE), createdAt = NOW - 10 * DAY)

            vm().also { it.load("med", NINE).join() }.state.value.let { assertIs<AnswerState.Loaded>(it) }
            vm().load("chore", NINE).join()

            val rows = stored().associateBy { it.reminderId }
            assertEquals(InstanceStatus.Pending, rows.getValue("med").status)
            assertEquals(ReminderKind.Medication, rows.getValue("med").reminderKind)
            assertEquals(ReminderKind.Task, rows.getValue("chore").reminderKind)
            assertEquals(NINE, rows.getValue("chore").scheduledTime)
        }

    @Test
    fun `opening the same moment twice finds the row it made rather than opening a second`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)

            vm().load("med", NINE).join()
            vm().load("med", NINE).join()

            assertEquals(1, stored().size)
        }

    @Test
    fun `coming back to a snoozed moment finds its row, because a snooze is a deferral and not an answer`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val first = vm().also { it.load("med", NINE).join() }
            first.snooze().join()

            val again = vm()
            again.load("med", NINE).join()

            assertEquals(1, stored().size)
            val loaded = assertIs<AnswerState.Loaded>(again.state.value)
            assertEquals(InstanceStatus.Snoozed, loaded.instance.status)
        }

    @Test
    fun `a dose the sweep marked missed can still be recorded as taken, on the same row`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val missed = container.instances.create(
                CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, NINE, status = InstanceStatus.Missed),
            )
            val vm = vm()
            vm.load("med", NINE).join()

            assertEquals(missed.id, assertIs<AnswerState.Loaded>(vm.state.value).instance.id)
            vm.complete().join()

            val row = stored().single()
            assertEquals(missed.id, row.id)
            assertEquals(InstanceStatus.Completed, row.status)
            assertEquals(NOW, row.completedAt)
        }

    @Test
    fun `completing a medication records it, says taken, and shows the confirmation`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()

            vm.complete().join()

            val row = stored().single()
            assertEquals(InstanceStatus.Completed, row.status)
            assertEquals(NOW, row.completedAt)
            val done = assertIs<AnswerState.Loaded>(vm.state.value)
            assertEquals(Action.Done, done.action)
            assertEquals(NOW, done.recordedAt)
            assertEquals(listOf("Recorded as taken" to ToastKind.Success), toasts.shown.map { it.message to it.kind })
        }

    @Test
    fun `completing a task says done, and stores the same Kind-neutral status`() =
        withUi(NOW) {
            reminder("chore", ReminderKind.Task, onceTimes(NINE), createdAt = NOW - DAY)
            val vm = vm()
            vm.load("chore", NINE).join()

            vm.complete().join()

            assertEquals(InstanceStatus.Completed, stored().single().status)
            assertEquals("Recorded as done", toasts.shown.single().message)
        }

    @Test
    fun `skipping records an answer, not an absence`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()

            vm.skip().join()

            assertEquals(InstanceStatus.Skipped, stored().single().status)
            assertEquals(Action.Done, assertIs<AnswerState.Loaded>(vm.state.value).action)
            assertEquals("Recorded as skipped", toasts.shown.single().message)
        }

    @Test
    fun `snoozing writes the deferral down, counts it, and arms the same moment`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()

            vm.snooze().join()

            val row = stored().single()
            assertEquals(InstanceStatus.Snoozed, row.status)
            assertEquals(NOW + 15 * 60_000, row.snoozedUntil)
            assertEquals(1, row.snoozeCount)
            // The alarm names the very instant the row stores: one reading of the clock.
            assertTrue(alarms().any { it.triggerAtTime == NOW + 15 * 60_000 }, "no alarm at the snoozed moment")
            assertEquals("Reminder snoozed 15 minutes" to ToastKind.Info, toasts.shown.single().let { it.message to it.kind })
        }

    @Test
    fun `snoozing a task arms its own Tier and not the alarm clock`() =
        withUi(NOW) {
            reminder("chore", ReminderKind.Task, onceTimes(NINE), createdAt = NOW - DAY)
            val vm = vm()
            vm.load("chore", NINE).join()

            vm.snooze().join()

            assertEquals(InstanceStatus.Snoozed, stored().single().status)
            assertTrue(alarms().isEmpty(), "a task must not occupy an alarm slot")
            assertEquals("Reminder snoozed 15 minutes", toasts.shown.single().message)
        }

    @Test
    fun `an answer is followed by a reschedule`() =
        withUi(NOW) {
            reminder("later", ReminderKind.Medication, onceTimes(NOW + 2 * HOUR), createdAt = NOW - DAY)
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            assertTrue(alarms().isEmpty())
            val vm = vm()
            vm.load("med", NINE).join()

            vm.complete().join()

            assertTrue(alarms().any { it.triggerAtTime == NOW + 2 * HOUR }, "the reschedule did not run after the write")
        }

    @Test
    fun `a second tap while the first is recorded changes nothing`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            vm.complete().join()
            val queued = container.syncQueue.pending().size

            vm.skip().join()
            vm.complete().join()

            assertEquals(InstanceStatus.Completed, stored().single().status)
            assertEquals(queued, container.syncQueue.pending().size)
            assertEquals(1, toasts.shown.size)
        }

    @Test
    fun `a write that fails leaves the buttons live and says so`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")

            vm.complete().join()

            val loaded = assertIs<AnswerState.Loaded>(vm.state.value)
            assertEquals(Action.Idle, loaded.action)
            assertNotNull(loaded.error)
            assertNull(loaded.recordedAt)
            assertEquals(ToastKind.Error, toasts.shown.single().kind)
            assertEquals("Couldn't record it — will retry on next sync", toasts.shown.single().message)
        }

    @Test
    fun `each failed answer says what it was`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")

            vm.skip().join()
            vm.snooze().join()

            assertEquals(listOf("Couldn't record skip — will retry on next sync", "Couldn't snooze reminder"), toasts.shown.map { it.message })
        }

    @Test
    fun `a link with no reminder or no moment is an invalid link, not a crash`() =
        withUi(NOW) {
            val noId = vm().also { it.load(null, NINE).join() }
            val noTime = vm().also { it.load("med", null).join() }

            assertEquals(AnswerState.InvalidLink, noId.state.value)
            assertEquals(AnswerState.InvalidLink, noTime.state.value)
        }

    @Test
    fun `a reminder this device does not have says so`() =
        withUi(NOW) {
            val vm = vm()

            vm.load("gone", NINE).join()

            assertEquals(AnswerState.Failed("This reminder no longer exists."), vm.state.value)
        }

    @Test
    fun `a load that throws becomes a message`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")
            val vm = vm()

            vm.load("med", NINE).join()

            assertIs<AnswerState.Failed>(vm.state.value)
        }

    @Test
    fun `answering before anything is loaded does nothing`() =
        withUi(NOW) {
            val vm = vm()

            vm.complete().join()

            assertEquals(AnswerState.Loading, vm.state.value)
            assertTrue(toasts.shown.isEmpty())
        }

    @Test
    fun `snoozing follows the length chosen in Settings`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            container.settings.setSnoozeMinutes(5)
            val vm = vm()
            vm.load("med", NINE).join()

            vm.snooze().join()

            assertEquals(NOW + 5 * 60_000, stored().single().snoozedUntil)
            assertTrue(alarms().any { it.triggerAtTime == NOW + 5 * 60_000 }, "no alarm at the snoozed moment")
            assertEquals("Reminder snoozed 5 minutes", toasts.shown.single().message)
        }

    @Test
    fun `picking a time defers the moment to that clock time, as a snooze, for a medication`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            val at = Instant.parse("2026-08-06T15:30:00Z").toEpochMilli()

            vm.rescheduleTo(15, 30).join()

            val row = stored().single()
            assertEquals(InstanceStatus.Snoozed, row.status)
            assertEquals(at, row.snoozedUntil)
            assertEquals(1, row.snoozeCount)
            assertTrue(alarms().any { it.triggerAtTime == at }, "no alarm at the picked time")
            assertEquals(Action.Done, assertIs<AnswerState.Loaded>(vm.state.value).action)
            assertEquals("Reminder set for 3:30 PM" to ToastKind.Info, toasts.shown.single().let { it.message to it.kind })
        }

    @Test
    fun `picking a time works for a task too`() =
        withUi(NOW) {
            reminder("chore", ReminderKind.Task, onceTimes(NINE), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("chore", NINE).join()

            vm.rescheduleTo(21, 0).join()

            val row = stored().single()
            assertEquals(InstanceStatus.Snoozed, row.status)
            assertEquals(Instant.parse("2026-08-06T21:00:00Z").toEpochMilli(), row.snoozedUntil)
        }

    @Test
    fun `a picked time that is not ahead writes nothing and says so`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()

            vm.rescheduleTo(8, 0).join()

            assertEquals(InstanceStatus.Pending, stored().single().status)
            assertEquals(Action.Idle, assertIs<AnswerState.Loaded>(vm.state.value).action)
            assertEquals("Pick a time later today" to ToastKind.Error, toasts.shown.single().let { it.message to it.kind })
        }

    @Test
    fun `a picked time that armed nothing is not promised`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = AnswerViewModel(container, toasts::add, armSnooze = { _, _, _ -> false })
            vm.load("med", NINE).join()

            vm.rescheduleTo(15, 30).join()

            assertEquals(InstanceStatus.Snoozed, stored().single().status)
            assertEquals("Put off for now", toasts.shown.single().message)
        }

    @Test
    fun `taking a take back reopens the moment, gives the pills back, and the buttons are live again`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY) { it.copy(pillsRemaining = 30.0) }
            val vm = vm()
            vm.load("med", NINE).join()
            vm.complete().join()
            assertEquals(29.0, container.reminders.get("med")!!.pillsRemaining)

            vm.undo().join()

            val loaded = assertIs<AnswerState.Loaded>(vm.state.value)
            assertEquals(Action.Idle, loaded.action)
            assertNull(loaded.recordedAt)
            assertEquals(InstanceStatus.Pending, loaded.instance.status)
            assertEquals(InstanceStatus.Pending, stored().single().status)
            assertEquals(30.0, container.reminders.get("med")!!.pillsRemaining)
            assertEquals("Answer taken back" to ToastKind.Info, toasts.shown.last().let { it.message to it.kind })
        }

    @Test
    fun `after an undo the moment can be answered again, differently`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            vm.skip().join()
            vm.undo().join()

            vm.complete().join()

            assertEquals(InstanceStatus.Completed, stored().single().status)
        }

    @Test
    fun `an undo of a snooze cancels the deferred alarm`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            vm.snooze().join()
            assertTrue(alarms().any { it.triggerAtTime == NOW + 15 * 60_000 })

            vm.undo().join()

            assertTrue(alarms().none { it.triggerAtTime == NOW + 15 * 60_000 })
        }

    @Test
    fun `undo says when the moment is past its grace period`() =
        withUi(NINE + 3 * HOUR) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NINE - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            vm.skip().join()

            vm.undo().join()

            assertEquals("Answer taken back. This one is past its grace period and will count as missed.", toasts.shown.last().message)
        }

    @Test
    fun `there is nothing to undo until an answer has been recorded`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()

            vm.undo().join()

            assertEquals(Action.Idle, assertIs<AnswerState.Loaded>(vm.state.value).action)
            assertTrue(toasts.shown.isEmpty())
        }

    @Test
    fun `an undo that is refused leaves the answer standing and says why`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = vm()
            vm.load("med", NINE).join()
            vm.skip().join()
            // The same moment is opened again elsewhere (issue #21).
            container.instances.create(CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, NINE))

            vm.undo().join()

            val loaded = assertIs<AnswerState.Loaded>(vm.state.value)
            assertEquals(Action.Done, loaded.action)
            assertEquals(InstanceStatus.Skipped, stored().single { it.status != InstanceStatus.Pending }.status)
            assertEquals("This moment was opened again. Answer that one instead." to ToastKind.Error, toasts.shown.last().let { it.message to it.kind })
        }

    @Test
    fun `an undo that fails keeps the answer, shows the error and can be tried again`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = AnswerViewModel(container, toasts::add, undoAnswer = { error("disk full") })
            vm.load("med", NINE).join()
            vm.skip().join()

            vm.undo().join()

            val loaded = assertIs<AnswerState.Loaded>(vm.state.value)
            assertEquals(Action.Done, loaded.action)
            assertEquals("disk full", loaded.error)
            assertEquals("Couldn't take that back" to ToastKind.Error, toasts.shown.last().let { it.message to it.kind })
        }

    @Test
    fun `a snooze that armed nothing is not promised`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)
            val vm = AnswerViewModel(container, toasts::add, armSnooze = { _, _, _ -> false })
            vm.load("med", NINE).join()

            vm.snooze().join()

            // The deferral is still recorded: the row is what syncs.
            assertEquals(InstanceStatus.Snoozed, stored().single().status)
            assertEquals("Put off for now" to ToastKind.Info, toasts.shown.single().let { it.message to it.kind })
        }
}
