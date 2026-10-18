package com.taskrx.mobile.ui

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.ui.today.TodayViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val MIDNIGHT = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli()
private val UTC = ZoneId.of("UTC")

/**
 * The Today timeline's behaviour as a person sees it. The rules (ordering, `next`,
 * deduplication) are :core's and tested there; these cases pin what this screen adds: both
 * Kinds on one list, handled rows staying visible, the sweep's verdict arriving, previous
 * days, and the day it will not move past.
 */
@RunWith(RobolectricTestRunner::class)
class TodayViewModelTest {
    private fun com.taskrx.mobile.Fixture.vm() = TodayViewModel(container, zone = { UTC }, log = { _, _ -> })

    @Test
    fun `both Kinds are one list in time order, and the first still to come is next`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Metformin")
            reminder("task", ReminderKind.Task, onceTimes(MIDNIGHT + 10 * HOUR), createdAt = MIDNIGHT - DAY, name = "Call pharmacy")
            val vm = vm()

            vm.refresh().join()

            val state = vm.state.value
            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals(listOf("Metformin", "Call pharmacy"), state.rows.map { it.name })
            assertEquals(listOf(ReminderKind.Medication, ReminderKind.Task), state.rows.map { it.kind })
            assertEquals(listOf(true, false), state.rows.map { it.isNext })
        }

    @Test
    fun `next is the upcoming one, not the first of the day`() =
        withUi(MIDNIGHT + 9 * HOUR + 30 * 60_000) {
            reminder("a", schedule = dailyTimes(listOf("09:00", "13:00")), createdAt = MIDNIGHT - 10 * DAY, name = "A")
            val vm = vm()

            vm.refresh().join()

            val rows = vm.state.value.rows
            assertEquals(listOf(false, true), rows.map { it.isNext })
        }

    @Test
    fun `the time a dose was taken can be moved within its day, and the row shows it`() =
        withUi(MIDNIGHT + 9 * HOUR + 30 * 60_000) {
            val r = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val instance = container.instances.create(CreateReminderInstanceInput(USER, r.id, r.kind, MIDNIGHT + 9 * HOUR))
            container.instances.markCompleted(instance.id, now)
            val vm = vm()
            vm.refresh().join()

            vm.changeTakenTime(vm.state.value.rows.single(), 9, 10).join()

            assertEquals(MIDNIGHT + 9 * HOUR + 10 * 60_000, vm.state.value.rows.single().completedAt)
            assertEquals(MIDNIGHT + 9 * HOUR + 10 * 60_000, container.instances.list(com.taskrx.mobile.data.InstanceFilter(USER)).single { it.id == instance.id }.completedAt)
            assertEquals("Time updated", container.toaster.current.value?.message)
        }

    @Test
    fun `a time that has not happened yet is refused and nothing changes`() =
        withUi(MIDNIGHT + 9 * HOUR + 30 * 60_000) {
            val r = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val instance = container.instances.create(CreateReminderInstanceInput(USER, r.id, r.kind, MIDNIGHT + 9 * HOUR))
            container.instances.markCompleted(instance.id, now)
            val vm = vm()
            vm.refresh().join()

            vm.changeTakenTime(vm.state.value.rows.single(), 23, 0).join()

            assertEquals(now, vm.state.value.rows.single().completedAt)
            assertEquals("That time hasn't happened yet", container.toaster.current.value?.message)
        }

    @Test
    fun `only a completed row has a time to change`() =
        withUi(MIDNIGHT + 9 * HOUR + 30 * 60_000) {
            val r = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val instance = container.instances.create(CreateReminderInstanceInput(USER, r.id, r.kind, MIDNIGHT + 9 * HOUR))
            container.instances.markMissed(instance.id)
            val vm = vm()
            vm.refresh().join()
            val missed = vm.state.value.rows.single { it.instanceId == instance.id }

            vm.changeTakenTime(missed, 9, 10).join()

            assertEquals(InstanceStatus.Missed, vm.state.value.rows.single { it.instanceId == instance.id }.status)
            assertNull(container.toaster.current.value)
        }

    @Test
    fun `a handled row stays visible in its handled state`() =
        withUi(MIDNIGHT + 9 * HOUR + 30 * 60_000) {
            val r = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val instance =
                container.instances.create(CreateReminderInstanceInput(USER, r.id, r.kind, MIDNIGHT + 9 * HOUR))
            container.instances.markCompleted(instance.id, now)
            val vm = vm()

            vm.refresh().join()

            val row = vm.state.value.rows.single()
            assertEquals(InstanceStatus.Completed, row.status)
            assertFalse(row.isNext)
        }

    @Test
    fun `a moment past its Grace Period reads Missed after the sweep, and stays on the day`() =
        withUi(MIDNIGHT + 12 * HOUR + 30 * 60_000) {
            reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val vm = vm()

            vm.refresh().join()

            val row = vm.state.value.rows.single()
            assertEquals(InstanceStatus.Missed, row.status)
            assertEquals(MIDNIGHT + 9 * HOUR, row.scheduledTime)
        }

    @Test
    fun `a failed sweep leaves the day as it was and is logged, not shown`() =
        withUi(MIDNIGHT + 12 * HOUR + 30 * 60_000) {
            reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
            val logged = mutableListOf<String>()
            val vm = TodayViewModel(container, zone = { UTC }, sweep = { _, _ -> error("sweep broke") }, log = { what, _ -> logged += what })

            vm.refresh().join()

            assertEquals(listOf(InstanceStatus.Pending), vm.state.value.rows.map { it.status })
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
            assertEquals(1, logged.size)
        }

    @Test
    fun `a day that cannot be read says so instead of showing an empty one`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            val vm = vm()
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminders")

            vm.refresh().join()

            assertTrue(vm.state.value.error != null)
            assertFalse(vm.state.value.loading)
        }

    @Test
    fun `an empty day is empty, not an error`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            val vm = vm()

            vm.refresh().join()

            assertTrue(vm.state.value.rows.isEmpty())
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }

    @Test
    fun `a previous day shows what happened on it, and cannot go forward past today`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            val r = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Metformin")
            val yesterday = MIDNIGHT - DAY + 9 * HOUR
            val instance = container.instances.create(CreateReminderInstanceInput(USER, r.id, r.kind, yesterday))
            container.instances.markCompleted(instance.id, yesterday + 60_000)
            val vm = vm()
            vm.refresh().join()

            vm.previousDay().join()

            assertEquals(-1, vm.state.value.dayOffset)
            assertEquals(MIDNIGHT - DAY, vm.state.value.windowStart)
            assertEquals(listOf(InstanceStatus.Completed), vm.state.value.rows.map { it.status })

            vm.nextDay().join()
            assertEquals(0, vm.state.value.dayOffset)
            vm.nextDay().join()
            assertEquals(0, vm.state.value.dayOffset)
            assertEquals(MIDNIGHT, vm.state.value.windowStart)
        }

    @Test
    fun `back to today from several days ago`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            val vm = vm()
            vm.previousDay().join()
            vm.previousDay().join()
            assertEquals(-2, vm.state.value.dayOffset)

            vm.today().join()

            assertEquals(0, vm.state.value.dayOffset)
            assertEquals(MIDNIGHT, vm.state.value.windowStart)
        }

    @Test
    fun `a stopped reminder's recorded history is still part of the day`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val r = reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Old med")
            val instance = container.instances.create(CreateReminderInstanceInput(USER, r.id, r.kind, MIDNIGHT + 9 * HOUR))
            container.instances.markCompleted(instance.id, MIDNIGHT + 9 * HOUR)
            container.reminders.softDelete(r.id)
            val vm = vm()

            vm.refresh().join()

            assertEquals(listOf("Old med"), vm.state.value.rows.map { it.name })
        }

    @Test
    fun `a pull-to-refresh shows the spinner and clears it`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            val vm = vm()

            vm.refresh(pulled = true).join()

            assertFalse(vm.state.value.refreshing)
        }

    @Test
    fun `only the signed-in user's reminders are on the day`() =
        withUi(MIDNIGHT + 8 * HOUR) {
            reminder("mine", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Mine")
            container.reminders.create(
                com.taskrx.mobile.data.CreateReminderInput(
                    userId = "someone-else",
                    kind = ReminderKind.Medication,
                    name = "Theirs",
                    schedule = dailyTimes(listOf("09:00")),
                ),
            )
            val vm = vm()

            vm.refresh().join()

            assertEquals(listOf("Mine"), vm.state.value.rows.map { it.name })
        }
}
