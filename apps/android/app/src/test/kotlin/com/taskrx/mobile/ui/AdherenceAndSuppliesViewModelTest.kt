package com.taskrx.mobile.ui

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.refill.RefillProjection
import com.taskrx.mobile.DAY
import com.taskrx.mobile.USER
import com.taskrx.mobile.ui.adherence.AdherenceViewModel
import com.taskrx.mobile.ui.components.ToastKind
import com.taskrx.mobile.ui.supplies.SuppliesViewModel
import com.taskrx.mobile.ui.supplies.SupplyDraft
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

/** 08:00, an hour before the day's first dose. Test JVMs run at UTC, as :core's suite does. */
private val NOW = Instant.parse("2026-08-13T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class AdherenceViewModelTest {
    @Test
    fun `the figure counts the doses nobody answered, because the sweep ran first`() =
        withUi(NOW) {
            // Due at 09:00 on each of the last three days and answered on none of them.
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 3 * DAY)
            val vm = AdherenceViewModel(container)

            vm.refresh().join()

            val stats = vm.state.value.reading!!.stats
            assertEquals(0.0, stats.percent)
            assertTrue(stats.missed > 0)
            assertFalse(vm.state.value.loading)
            assertNull(vm.state.value.error)
        }

    @Test
    fun `with nothing due there is no reading to be afraid of`() =
        withUi(NOW) {
            reminder("task", ReminderKind.Task, noneTimes())
            val vm = AdherenceViewModel(container)

            vm.refresh().join()

            assertEquals(0, vm.state.value.reading!!.stats.total)
        }

    @Test
    fun `a read that fails is shown, and the figure from before it is not kept`() =
        withUi(NOW) {
            reminder("med", schedule = dailyTimes(listOf("09:00")), createdAt = NOW - 3 * DAY)
            val vm = AdherenceViewModel(container)
            vm.refresh().join()
            assertNotNull(vm.state.value.reading)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")

            vm.refresh(pulled = true).join()

            assertNotNull(vm.state.value.error)
            assertNull(vm.state.value.reading)
            assertFalse(vm.state.value.refreshing)
        }
}

@RunWith(RobolectricTestRunner::class)
class SuppliesViewModelTest {
    private val sevenAm = Instant.parse("2026-08-13T08:00:00Z").toEpochMilli()

    private suspend fun com.taskrx.mobile.Fixture.medication(pills: Double?, perDose: Double? = 1.0) =
        reminder("med", schedule = dailyTimes(listOf("09:00")), name = "Atorvastatin") {
            it.copy(pillsRemaining = pills, pillsPerDose = perDose, refillReminderDays = 7)
        }

    @Test
    fun `opening the screen raises the Refill Reminder and says it did`() =
        withUi(sevenAm) {
            medication(3.0)
            val vm = SuppliesViewModel(container)

            vm.refresh().join()

            val state = vm.state.value
            assertEquals(listOf("Refill Atorvastatin"), state.raised)
            assertEquals(listOf("Refill Atorvastatin"), container.reminders.list(USER, ReminderKind.Task).map { it.name })
            assertEquals(listOf("Atorvastatin"), state.rows.map { it.medication.name })
            assertFalse(state.loading)
        }

    @Test
    fun `a second look raises nothing and says nothing`() =
        withUi(sevenAm) {
            medication(3.0)
            val vm = SuppliesViewModel(container)
            vm.refresh().join()

            vm.refresh().join()

            assertEquals(emptyList(), vm.state.value.raised)
            assertEquals(1, container.reminders.list(USER, ReminderKind.Task).size)
        }

    @Test
    fun `an unrecorded supply is not projected`() =
        withUi(sevenAm) {
            medication(pills = null)
            val vm = SuppliesViewModel(container)

            vm.refresh().join()

            assertIs<RefillProjection.Unprojectable>(vm.state.value.rows.single().projection)
            assertEquals(emptyList(), vm.state.value.raised)
        }

    @Test
    fun `collecting a refill re-projects and withdraws the warning, saying so`() =
        withUi(sevenAm) {
            medication(3.0)
            val vm = SuppliesViewModel(container)
            vm.refresh().join()
            vm.edit("med")
            assertEquals("med", vm.state.value.editingId)

            vm.saveSupply("med", SupplyDraft(30.0, 1.0, 7)).join()

            val state = vm.state.value
            assertNull(state.editingId)
            assertEquals(listOf("Refill Atorvastatin"), state.retired)
            assertEquals(emptyList(), container.reminders.list(USER, ReminderKind.Task))
            assertEquals(30.0, container.reminders.get("med")!!.pillsRemaining)
        }

    @Test
    fun `clearing a box clears the stored value`() =
        withUi(sevenAm) {
            medication(30.0)
            val vm = SuppliesViewModel(container)
            vm.refresh().join()

            vm.saveSupply("med", SupplyDraft(null, null, null)).join()

            val row = container.reminders.get("med")!!
            assertNull(row.pillsRemaining)
            assertNull(row.pillsPerDose)
            assertNull(row.refillReminderDays)
        }

    @Test
    fun `cancelling the editor changes nothing`() =
        withUi(sevenAm) {
            medication(30.0)
            val vm = SuppliesViewModel(container)
            vm.refresh().join()
            vm.edit("med")

            vm.cancelEdit()

            assertNull(vm.state.value.editingId)
            assertEquals(30.0, container.reminders.get("med")!!.pillsRemaining)
        }

    @Test
    fun `a supply that cannot be saved is a toast, and the screen stays up`() =
        withUi(sevenAm) {
            medication(30.0)
            val vm = SuppliesViewModel(container)
            vm.refresh().join()
            database.openHelper.writableDatabase.execSQL("ALTER TABLE reminders RENAME TO reminders_gone")

            vm.saveSupply("med", SupplyDraft(5.0, 1.0, 7)).join()

            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
            assertNull(vm.state.value.error)
            assertEquals(1, vm.state.value.rows.size)
        }

    @Test
    fun `a read that fails is shown`() =
        withUi(sevenAm) {
            val vm = SuppliesViewModel(container)
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminders")

            vm.refresh(pulled = true).join()

            assertNotNull(vm.state.value.error)
            assertFalse(vm.state.value.refreshing)
            assertFalse(vm.state.value.loading)
        }
}
