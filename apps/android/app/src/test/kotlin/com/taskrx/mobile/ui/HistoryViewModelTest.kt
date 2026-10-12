package com.taskrx.mobile.ui

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.noneTimes
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.ui.history.HistoryViewModel
import com.taskrx.mobile.ui.history.UNKNOWN_REMINDER
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class HistoryViewModelTest {
    private suspend fun com.taskrx.mobile.Fixture.record(id: String, kind: ReminderKind, at: Long, status: InstanceStatus = InstanceStatus.Completed) =
        container.instances.create(CreateReminderInstanceInput(USER, id, kind, at, status, completedAt = if (status == InstanceStatus.Completed) at else null))

    @Test
    fun `both Kinds are listed, newest first, each titled from its own Reminder`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, name = "Metformin") { it.copy(dosage = "500mg") }
            reminder("task", ReminderKind.Task, noneTimes(), name = "Call pharmacy")
            record("med", ReminderKind.Medication, NOW - 5 * HOUR)
            record("task", ReminderKind.Task, NOW - HOUR)
            val vm = HistoryViewModel(container)

            vm.refresh().join()

            val items = vm.state.value.items
            assertFalse(vm.state.value.loading)
            assertEquals(listOf("Call pharmacy", "Metformin"), items.map { it.name })
            assertEquals(listOf(null, "500mg"), items.map { it.dosage })
        }

    @Test
    fun `a stopped Reminder still names its history`() =
        withUi(NOW) {
            reminder("med", name = "Metformin")
            record("med", ReminderKind.Medication, NOW - HOUR)
            container.reminders.softDelete("med")
            val vm = HistoryViewModel(container)

            vm.refresh().join()

            assertEquals(listOf("Metformin"), vm.state.value.items.map { it.name })
        }

    @Test
    fun `a record whose parent this device has not seen is named plainly, not hidden`() =
        withUi(NOW) {
            reminder("med")
            record("med", ReminderKind.Medication, NOW - HOUR)
            database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
            database.openHelper.writableDatabase.execSQL("DELETE FROM reminders")
            val vm = HistoryViewModel(container)

            vm.refresh().join()

            assertEquals(listOf(UNKNOWN_REMINDER), vm.state.value.items.map { it.name })
        }

    @Test
    fun `an unreadable history says so`() =
        withUi(NOW) {
            val vm = HistoryViewModel(container)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")

            vm.refresh().join()

            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }
}
