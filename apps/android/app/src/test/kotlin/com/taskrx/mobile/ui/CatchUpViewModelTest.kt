package com.taskrx.mobile.ui

import com.taskrx.core.batch.BatchAction
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.catchup.CatchUpViewModel
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val MIDNIGHT = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli()
private val UTC = ZoneId.of("UTC")

/** The Catch up screen's behaviour: what it lists, and that a bulk answer writes, stamps and undoes correctly. */
@RunWith(RobolectricTestRunner::class)
class CatchUpViewModelTest {
    private fun Fixture.vm() = CatchUpViewModel(container, zone = { UTC }, log = { _, _ -> })

    private suspend fun Fixture.twoMeds() {
        reminder("a", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "A")
        reminder("b", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "B")
    }

    private suspend fun Fixture.stored() = container.instances.list(InstanceFilter(USER))

    @Test
    fun `lists the last days' missed doses, newest day first, and nothing already answered`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            twoMeds()
            val vm = vm()

            vm.refresh().join()

            val days = vm.state.value.days
            assertEquals(listOf(MIDNIGHT, MIDNIGHT - DAY), days.map { it.dayStart }.take(2))
            assertEquals(listOf("A", "B"), days[0].rows.map { it.name })
            assertTrue(vm.state.value.total >= 4)
        }

    @Test
    fun `done stamps each dose with the moment it was due`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            twoMeds()
            val vm = vm()
            vm.refresh().join()
            val yesterday = vm.state.value.days[1]

            vm.answer(yesterday.rows, BatchAction.Take).join()

            val done = stored().filter { it.status == InstanceStatus.Completed }
            assertEquals(2, done.size)
            assertEquals(setOf(yesterday.dayStart + 9 * HOUR), done.map { it.completedAt }.toSet())
            assertTrue(vm.state.value.days.none { it.dayStart == yesterday.dayStart })
            assertEquals("2 taken", vm.state.value.undo?.message)
        }

    @Test
    fun `skip skips them`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            twoMeds()
            val vm = vm()
            vm.refresh().join()

            vm.answer(vm.state.value.days.flatMap { it.rows }, BatchAction.Skip).join()

            assertEquals(setOf(InstanceStatus.Skipped), stored().map { it.status }.toSet())
            assertEquals(0, vm.state.value.total)
            assertEquals("${stored().size} skipped", vm.state.value.undo?.message)
        }

    @Test
    fun `undo takes the whole batch back`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            twoMeds()
            val vm = vm()
            vm.refresh().join()
            val before = vm.state.value.total

            vm.answer(vm.state.value.days.flatMap { it.rows }, BatchAction.Take).join()
            vm.undo().join()

            assertNull(vm.state.value.undo)
            assertEquals(before, vm.state.value.total)
            assertEquals("Taken back", container.toaster.current.value?.message)
        }

    @Test
    fun `one row answered leaves the other alone and names the row`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            twoMeds()
            val vm = vm()
            vm.refresh().join()
            val a = vm.state.value.days[0].rows.single { it.name == "A" }

            vm.answer(listOf(a), BatchAction.Take).join()

            val done = stored().filter { it.status == InstanceStatus.Completed }
            assertEquals(1, done.size)
            assertEquals(a.reminderId, done.single().reminderId)
            assertEquals(MIDNIGHT + 9 * HOUR, done.single().completedAt)
            assertEquals(listOf("B"), vm.state.value.days[0].rows.map { it.name })
            assertEquals("A taken", vm.state.value.undo?.message)
        }

    @Test
    fun `a task is done, not taken, and a skipped row is skipped`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            reminder("t", kind = ReminderKind.Task, schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Water plants")
            reminder("m", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Metformin")
            val vm = vm()
            vm.refresh().join()
            val today = vm.state.value.days[0].rows
            val task = today.single { it.name == "Water plants" }
            val med = today.single { it.name == "Metformin" }

            vm.answer(listOf(task), BatchAction.Take).join()
            assertEquals("Water plants done", vm.state.value.undo?.message)

            vm.answer(listOf(med), BatchAction.Skip).join()
            assertEquals("Metformin skipped", vm.state.value.undo?.message)
        }

    @Test
    fun `a batch of tasks is done, and a batch with any task in it is not called taken`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            for (n in listOf("t1", "t2")) reminder(n, kind = ReminderKind.Task, schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = n)
            reminder("m", schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY, name = "Metformin")
            val vm = vm()
            vm.refresh().join()
            val today = vm.state.value.days[0].rows

            vm.answer(today.filter { it.kind == ReminderKind.Task }, BatchAction.Take).join()
            assertEquals("2 done", vm.state.value.undo?.message)

            vm.answer(vm.state.value.days[1].rows, BatchAction.Take).join()
            assertEquals("3 done", vm.state.value.undo?.message)
        }

    @Test
    fun `undo takes back one row after it has left the list`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            twoMeds()
            val vm = vm()
            vm.refresh().join()
            val a = vm.state.value.days[0].rows.single { it.name == "A" }

            vm.answer(listOf(a), BatchAction.Take).join()
            assertTrue(vm.state.value.days[0].rows.none { it.name == "A" })
            vm.undo().join()

            assertTrue(vm.state.value.days[0].rows.any { it.name == "A" })
            assertTrue(stored().none { it.status == InstanceStatus.Completed })
        }

    @Test
    fun `answering nothing offers no undo`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val vm = vm()
            vm.refresh().join()

            vm.answer(emptyList(), BatchAction.Take).join()

            assertNull(vm.state.value.undo)
        }

    @Test
    fun `undo with nothing to undo does nothing`() =
        withUi(MIDNIGHT + 12 * HOUR) {
            val vm = vm()

            vm.undo().join()

            assertNull(container.toaster.current.value)
        }
}
