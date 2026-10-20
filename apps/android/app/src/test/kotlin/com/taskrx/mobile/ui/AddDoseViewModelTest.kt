package com.taskrx.mobile.ui

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.dose.AddDoseViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val MIDNIGHT = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli()
private val UTC = ZoneId.of("UTC")

@RunWith(RobolectricTestRunner::class)
class AddDoseViewModelTest {
    private fun com.taskrx.mobile.Fixture.vm(toasts: ToastLog = ToastLog(), logged: MutableList<String> = mutableListOf()) =
        AddDoseViewModel(container, zone = { UTC }, toast = toasts::add, log = { what, _ -> logged += what })

    @Test
    fun `every medication is offered, running ones first, tasks never`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            reminder("z", name = "Zinc")
            reminder("a", name = "Aspirin")
            reminder("old", name = "Amoxicillin")
            container.reminders.update("old", com.taskrx.mobile.data.UpdateReminderPatch(active = false))
            reminder("t", ReminderKind.Task, name = "Call pharmacy")
            val vm = vm()

            vm.refresh().join()

            assertEquals(listOf("Aspirin", "Zinc", "Amoxicillin"), vm.state.value.medications.map { it.name })
            assertFalse(vm.state.value.loading)
        }

    @Test
    fun `a dose is recorded as completed at the chosen time and shows on the day`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val med = reminder("a", name = "Aspirin", schedule = noneTimes())
            val vm = vm()

            vm.record(med, 10, 30).join()

            val row = container.instances.list(InstanceFilter(USER)).single()
            assertEquals(InstanceStatus.Completed, row.status)
            assertEquals(MIDNIGHT + 10 * HOUR + 30 * 60_000, row.scheduledTime)
            assertEquals(row.scheduledTime, row.completedAt)
            assertTrue(vm.state.value.saved)
        }

    @Test
    fun `a time that has not happened yet is refused and nothing is written`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val med = reminder("a")
            val toasts = ToastLog()
            val vm = vm(toasts)

            vm.record(med, 13, 0).join()

            assertEquals(emptyList(), container.instances.list(InstanceFilter(USER)))
            assertEquals("That time hasn't happened yet", toasts.shown.single().message)
            assertFalse(vm.state.value.saved)
        }

    @Test
    fun `the minute it is now is allowed`() =
        withUi(MIDNIGHT + 12 * HOUR + 30_000) {
            val med = reminder("a")

            vm().record(med, 12, 0).join()

            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
        }

    @Test
    fun `a minute already answered is refused rather than hidden behind the first`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val med = reminder("a")
            val toasts = ToastLog()
            val vm = vm(toasts)
            vm.record(med, 10, 0).join()

            vm.record(med, 10, 0).join()

            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
            assertEquals("Already recorded at that time", toasts.shown.last().message)
        }

    @Test
    fun `recording a scheduled moment that is still open answers it in place`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val med = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val open = container.instances.create(com.taskrx.mobile.data.CreateReminderInstanceInput(USER, med.id, med.kind, MIDNIGHT + 9 * HOUR))
            container.instances.markMissed(open.id)

            vm().record(med, 9, 0).join()

            val rows = container.instances.list(InstanceFilter(USER))
            assertEquals(1, rows.size)
            assertEquals(InstanceStatus.Completed, rows.single().status)
        }

    @Test
    fun `an extra dose of a medication that is stopped is still recorded`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val med = reminder("a")
            container.reminders.update("a", com.taskrx.mobile.data.UpdateReminderPatch(active = false))

            vm().record(med, 8, 0).join()

            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
        }

    @Test
    fun `a write that fails says so and does not go back`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val med = reminder("a")
            val toasts = ToastLog()
            val logged = mutableListOf<String>()
            val vm = vm(toasts, logged)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")

            vm.record(med, 10, 0).join()

            assertEquals(listOf("recording a dose failed"), logged)
            assertEquals("Couldn't record it", toasts.shown.single().message)
            assertFalse(vm.state.value.saved)
        }

    @Test
    fun `a list that cannot be read says so`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val vm = vm()
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminders")

            vm.refresh().join()

            assertTrue(vm.state.value.error != null)
            assertFalse(vm.state.value.loading)
        }
}
