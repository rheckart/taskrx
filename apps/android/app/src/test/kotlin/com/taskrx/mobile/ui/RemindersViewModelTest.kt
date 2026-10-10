package com.taskrx.mobile.ui

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.reminders.EditReminderViewModel
import com.taskrx.mobile.ui.reminders.EditState
import com.taskrx.mobile.ui.reminders.RemindersViewModel
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class RemindersViewModelTest {
    @Test
    fun `every live Reminder of either Kind is listed with when it was last done`() =
        withUi(NOW) {
            reminder("med", name = "Metformin", schedule = dailyTimes(listOf("09:00")))
            reminder("task", ReminderKind.Task, onceTimes(NOW + HOUR), name = "Call pharmacy")
            reminder("gone", name = "Old")
            container.reminders.softDelete("gone")
            container.instances.create(
                CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, NOW - HOUR, InstanceStatus.Completed, completedAt = NOW - HOUR),
            )
            val vm = RemindersViewModel(container)

            vm.refresh().join()

            val entries = vm.state.value.entries
            assertEquals(setOf("Metformin", "Call pharmacy"), entries.map { it.reminder.name }.toSet())
            assertEquals(NOW - HOUR, entries.single { it.reminder.id == "med" }.lastCompleted?.completedAt)
            assertNull(entries.single { it.reminder.id == "task" }.lastCompleted)
        }

    @Test
    fun `stopping one keeps its history, drops it from the list and silences its alarm`() =
        withUi(NOW) {
            val r = reminder("med", name = "Metformin", schedule = onceTimes(NOW + 2 * HOUR))
            container.instances.create(
                CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, NOW - HOUR, InstanceStatus.Completed, completedAt = NOW - HOUR),
            )
            container.notifications.rescheduleAfterWrite(USER)
            assertTrue(alarms().any { it.triggerAtTime == NOW + 2 * HOUR })
            val vm = RemindersViewModel(container)
            vm.refresh().join()

            vm.stop(r).join()

            assertTrue(vm.state.value.entries.isEmpty())
            assertFalse(container.reminders.get("med")!!.active)
            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
            assertTrue(alarms().none { it.triggerAtTime == NOW + 2 * HOUR })
            assertEquals("Stopped Metformin" to ToastKind.Success, container.toaster.current.value?.let { it.message to it.kind })
        }

    @Test
    fun `a stop that cannot be written says so`() =
        withUi(NOW) {
            val r = reminder("med")
            val vm = RemindersViewModel(container)
            vm.refresh().join()
            database.openHelper.writableDatabase.execSQL("ALTER TABLE reminders RENAME TO reminders_gone")

            vm.stop(r).join()

            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
        }

    @Test
    fun `an unreadable list says so`() =
        withUi(NOW) {
            val vm = RemindersViewModel(container)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminders")

            vm.refresh().join()

            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }

    // ---- edit ----

    private suspend fun com.taskrx.mobile.Fixture.editing(id: String): Pair<EditReminderViewModel, EditState.Editing> {
        val vm = EditReminderViewModel(container)
        vm.load(id).join()
        return vm to assertIs<EditState.Editing>(vm.state.value)
    }

    @Test
    fun `the form starts from what is stored`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), name = "Metformin") { it.copy(dosage = "500mg", instructions = "with food") }
            reminder("task", ReminderKind.Task, noneTimes(), name = "Call") { it.copy(instructions = "ask about repeat") }

            assertEquals("500mg", editing("med").second.detail)
            assertEquals("ask about repeat", editing("task").second.detail)
            assertEquals(dailyTimes(listOf("09:00")), editing("med").second.schedule)
        }

    @Test
    fun `an unknown, stopped or absent Reminder is not editable`() =
        withUi(NOW) {
            reminder("gone")
            container.reminders.softDelete("gone")
            for (id in listOf("nope", "gone", null)) {
                val vm = EditReminderViewModel(container)
                vm.load(id).join()
                assertEquals(EditState.NotFound, vm.state.value)
            }
        }

    @Test
    fun `another user's Reminder is not editable here`() =
        withUi(NOW) {
            container.reminders.create(
                com.taskrx.mobile.data.CreateReminderInput(id = "theirs", userId = "someone-else", kind = ReminderKind.Task, name = "Theirs", schedule = noneTimes()),
            )
            val vm = EditReminderViewModel(container)

            vm.load("theirs").join()

            assertEquals(EditState.NotFound, vm.state.value)
        }

    @Test
    fun `saving a medication changes name, dosage and Schedule, keeps its instructions and re-arms`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), name = "Metformin") { it.copy(dosage = "500mg", instructions = "with food") }
            val (vm) = editing("med")

            vm.setName(" Metformin XR ")
            vm.setDetail("  ")
            vm.setSchedule(onceTimes(NOW + 3 * HOUR))
            vm.save().join()

            val row = container.reminders.get("med")!!
            assertEquals("Metformin XR", row.name)
            assertNull(row.dosage)
            assertEquals("with food", row.instructions)
            assertEquals(onceTimes(NOW + 3 * HOUR), row.schedule)
            assertTrue((vm.state.value as EditState.Editing).saved)
            assertEquals("Saved", container.toaster.current.value?.message)
            assertTrue(alarms().any { it.triggerAtTime == NOW + 3 * HOUR })
        }

    @Test
    fun `saving a task changes its notes and leaves dosage alone`() =
        withUi(NOW) {
            reminder("task", ReminderKind.Task, noneTimes(), name = "Call") { it.copy(dosage = "stray") }
            val (vm) = editing("task")

            vm.setDetail(" ask about repeat ")
            vm.save().join()

            val row = container.reminders.get("task")!!
            assertEquals("ask about repeat", row.instructions)
            assertEquals("stray", row.dosage)
        }

    @Test
    fun `an unsavable form writes nothing`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), name = "Metformin")
            val (vm) = editing("med")
            val before = container.reminders.get("med")

            vm.setName(" ")
            vm.save().join()
            vm.setName("X")
            vm.setSchedule(dailyTimes(emptyList()))
            vm.save().join()

            assertEquals(before, container.reminders.get("med"))
        }

    @Test
    fun `a second save cannot write twice`() =
        withUi(NOW) {
            reminder("med", name = "A")
            val (vm) = editing("med")
            vm.setName("B")

            vm.save().join()
            val queued = container.syncQueue.pending().size
            vm.save().join()

            assertEquals(queued, container.syncQueue.pending().size)
        }

    @Test
    fun `a failed save keeps the form open and can be tried again`() =
        withUi(NOW) {
            reminder("med", name = "A")
            val (vm) = editing("med")
            vm.setName("B")
            database.openHelper.writableDatabase.execSQL("ALTER TABLE reminders RENAME TO reminders_gone")

            vm.save().join()

            val state = vm.state.value as EditState.Editing
            assertNotNull(state.error)
            assertFalse(state.saved)
            assertTrue(state.canSave)
            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
        }

    @Test
    fun `edits to a Reminder that is not loaded are ignored`() =
        withUi(NOW) {
            val vm = EditReminderViewModel(container)
            vm.setName("x")
            vm.save().join()
            assertEquals(EditState.Loading, vm.state.value)
        }
}
