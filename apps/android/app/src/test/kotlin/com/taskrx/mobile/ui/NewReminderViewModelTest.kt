package com.taskrx.mobile.ui

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.forms.NewReminderViewModel
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

private val NOW = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

/** The manual new-reminder forms, one per Kind. */
@RunWith(RobolectricTestRunner::class)
class NewReminderViewModelTest {
    private fun Fixture.vm(kind: ReminderKind) = NewReminderViewModel(container, kind)

    private suspend fun Fixture.saved() = container.reminders.list(USER, includeInactive = true)

    @Test
    fun `a task starts as a one-off, a medication as daily`() =
        withUi(NOW) {
            assertIs<OnceTimes>(vm(ReminderKind.Task).state.value.schedule)
            assertEquals(NOW + 3_600_000, (vm(ReminderKind.Task).state.value.schedule as OnceTimes).at)
            assertIs<DailyTimes>(vm(ReminderKind.Medication).state.value.schedule)
        }

    @Test
    fun `nothing can be saved until it has a name and a schedule that can come due`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Medication)
            assertFalse(vm.state.value.canSave)

            vm.setName("  ")
            assertFalse(vm.state.value.canSave)

            vm.setName("Metformin")
            assertTrue(vm.state.value.canSave)

            vm.setSchedule(dailyTimes(emptyList()))
            assertFalse(vm.state.value.canSave)

            vm.setSchedule(intervalTimes(0, NOW))
            assertFalse(vm.state.value.canSave)

            vm.setSchedule(NoneTimes)
            assertTrue(vm.state.value.canSave)
        }

    @Test
    fun `an unsavable form writes nothing`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Task)

            vm.save().join()

            assertTrue(saved().isEmpty())
        }

    @Test
    fun `a medication is saved with its dosage and nothing from the task form`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Medication)
            vm.setName(" Metformin ")
            vm.setDosage(" 500mg ")
            vm.setNotes("ignored: a medication has no notes box")

            vm.save().join()

            val row = saved().single()
            assertEquals(ReminderKind.Medication, row.kind)
            assertEquals("Metformin", row.name)
            assertEquals("500mg", row.dosage)
            assertNull(row.instructions)
            assertEquals(dailyTimes(listOf("09:00")), row.schedule)
            assertTrue(vm.state.value.saved)
            assertEquals("Medication added" to ToastKind.Success, container.toaster.current.value?.let { it.message to it.kind })
        }

    @Test
    fun `a task is saved with its notes and no dosage`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Task)
            vm.setName("Call the pharmacy")
            vm.setNotes(" ask about the repeat ")
            vm.setDosage("ignored: a task has no dosage")

            vm.save().join()

            val row = saved().single()
            assertEquals(ReminderKind.Task, row.kind)
            assertEquals("ask about the repeat", row.instructions)
            assertNull(row.dosage)
            assertEquals("Task added", container.toaster.current.value?.message)
        }

    @Test
    fun `a blank optional field is absent, not stored as empty text`() =
        withUi(NOW) {
            val med = vm(ReminderKind.Medication).also { it.setName("A"); it.setDosage("   ") }
            val task = vm(ReminderKind.Task).also { it.setName("B"); it.setNotes("") }

            med.save().join()
            task.save().join()

            assertTrue(saved().all { it.dosage == null && it.instructions == null })
        }

    @Test
    fun `a saved reminder is armed straight away`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Medication)
            vm.setName("Metformin")
            vm.setSchedule(dailyTimes(listOf("13:00")))

            vm.save().join()

            val alarms =
                org.robolectric.Shadows.shadowOf(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
                        .getSystemService(android.app.AlarmManager::class.java),
                ).scheduledAlarms
            assertTrue(alarms.any { it.triggerAtTime == NOW + 3_600_000 })
        }

    @Test
    fun `a second tap cannot write a second row`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Task)
            vm.setName("Once")

            vm.save().join()
            vm.save().join()

            assertEquals(1, saved().size)
        }

    @Test
    fun `a failed save keeps the form and can be tried again`() =
        withUi(NOW) {
            val vm = vm(ReminderKind.Task)
            vm.setName("Call")
            database.openHelper.writableDatabase.execSQL("ALTER TABLE reminders RENAME TO reminders_gone")

            vm.save().join()

            val state = vm.state.value
            assertNotNull(state.error)
            assertFalse(state.saved)
            assertTrue(state.canSave)
            assertEquals("Couldn't save — will retry on next sync", container.toaster.current.value?.message)
            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
        }
}
