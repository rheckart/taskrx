package com.taskrx.mobile.ui

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.backlog.BacklogViewModel
import com.taskrx.mobile.ui.backlog.canMoveToTimeline
import com.taskrx.mobile.ui.components.ToastKind
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

/**
 * The Backlog. What is Backlog and what completing one produces are :core's; these pin that
 * this screen asks them and persists the answer (ADR 0002).
 */
@RunWith(RobolectricTestRunner::class)
class BacklogViewModelTest {
    @Test
    fun `it lists the Reminders with no Schedule, of either Kind, and nothing else`() =
        withUi(NOW) {
            reminder("med", ReminderKind.Medication, noneTimes(), name = "Ibuprofen")
            reminder("task", ReminderKind.Task, noneTimes(), name = "Sort the garage")
            reminder("daily", schedule = dailyTimes(listOf("09:00")), name = "Metformin")
            reminder("empty", schedule = dailyTimes(emptyList()), name = "Misconfigured")
            reminder("stopped", schedule = noneTimes(), name = "Stopped")
            container.reminders.softDelete("stopped")
            val vm = BacklogViewModel(container)

            vm.refresh().join()

            val state = vm.state.value
            assertFalse(state.loading)
            assertNull(state.error)
            assertEquals(setOf("Ibuprofen", "Sort the garage"), state.entries.map { it.reminder.name }.toSet())
        }

    @Test
    fun `completing one records a Completed Instance at the moment of the act, and it stays in the Backlog`() =
        withUi(NOW) {
            val r = reminder("a", ReminderKind.Task, noneTimes(), name = "Sort the garage")
            val vm = BacklogViewModel(container)
            vm.refresh().join()
            now = NOW + 5 * HOUR

            vm.complete(r).join()

            val instance = container.instances.list(InstanceFilter(USER)).single()
            assertEquals(InstanceStatus.Completed, instance.status)
            assertEquals(NOW + 5 * HOUR, instance.scheduledTime)
            assertEquals(NOW + 5 * HOUR, instance.completedAt)
            assertEquals(ReminderKind.Task, instance.reminderKind)
            assertEquals("Recorded" to ToastKind.Success, container.toaster.current.value?.let { it.message to it.kind })
            val entry = vm.state.value.entries.single()
            assertEquals(instance.id, entry.lastCompleted?.id)
        }

    @Test
    fun `a double tap records once`() =
        withUi(NOW) {
            val r = reminder("a", schedule = noneTimes())
            val vm = BacklogViewModel(container)
            vm.refresh().join()

            val first = vm.complete(r)
            val second = vm.complete(r)
            first.join()
            second.join()

            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
            assertTrue(vm.state.value.busy.isEmpty())
        }

    @Test
    fun `a completion that cannot be written says so and records nothing`() =
        withUi(NOW) {
            val r = reminder("a", schedule = noneTimes())
            val vm = BacklogViewModel(container)
            vm.refresh().join()
            database.openHelper.writableDatabase.execSQL("ALTER TABLE reminder_instances RENAME TO instances_gone")

            vm.complete(r).join()

            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
            assertTrue(vm.state.value.busy.isEmpty())
        }

    @Test
    fun `giving one a time moves it onto the timeline and arms it`() =
        withUi(NOW) {
            val r = reminder("a", ReminderKind.Medication, noneTimes(), name = "Ibuprofen")
            val vm = BacklogViewModel(container)
            vm.refresh().join()

            vm.moveToTimeline(r, onceTimes(NOW + 2 * HOUR)).join()

            assertEquals(onceTimes(NOW + 2 * HOUR), container.reminders.get("a")?.schedule)
            assertTrue(vm.state.value.entries.isEmpty())
            assertEquals("Moved to your timeline", container.toaster.current.value?.message)
            assertTrue(alarms().any { it.triggerAtTime == NOW + 2 * HOUR })
        }

    @Test
    fun `giving one no time at all, or an unusable one, moves nothing`() =
        withUi(NOW) {
            val r = reminder("a", schedule = noneTimes())
            val vm = BacklogViewModel(container)
            vm.refresh().join()

            vm.moveToTimeline(r, noneTimes()).join()
            vm.moveToTimeline(r, dailyTimes(emptyList())).join()

            assertEquals(noneTimes(), container.reminders.get("a")?.schedule)
            assertEquals(1, vm.state.value.entries.size)
            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
        }

    @Test
    fun `only a well-formed Schedule with a moment can be moved to`() {
        assertTrue(canMoveToTimeline(onceTimes(NOW)))
        assertTrue(canMoveToTimeline(dailyTimes(listOf("09:00"))))
        assertFalse(canMoveToTimeline(noneTimes()))
        assertFalse(canMoveToTimeline(dailyTimes(emptyList())))
    }

    @Test
    fun `a backlog that cannot be read says so`() =
        withUi(NOW) {
            val vm = BacklogViewModel(container)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminders")

            vm.refresh().join()

            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }
}
