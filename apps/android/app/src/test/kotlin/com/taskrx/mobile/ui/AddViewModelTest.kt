package com.taskrx.mobile.ui

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.ui.add.AddViewModel
import com.taskrx.mobile.ui.add.copyFor
import com.taskrx.mobile.ui.add.namesSupply
import com.taskrx.mobile.ui.add.priorityLabel
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

private val NOW = Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

/**
 * Add by phrase, end to end through :core's parser and draft. The grammar is :core's
 * subject; these pin what this screen does with a reading: nothing saved until reviewed,
 * never a dead end, a Kind correction that cannot leave a task carrying a dose, and a
 * reschedule after the write.
 */
@RunWith(RobolectricTestRunner::class)
class AddViewModelTest {
    private fun Fixture.vm() = AddViewModel(container)

    private suspend fun Fixture.saved() = container.reminders.list(USER, includeInactive = true)

    @Test
    fun `reading a phrase opens an editable draft and saves nothing`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("atorvastatin 20mg every morning with food")

            vm.read()

            val state = vm.state.value
            val draft = assertNotNull(state.draft)
            assertTrue(state.understood)
            assertEquals(ReminderKind.Medication, draft.kind)
            assertEquals("atorvastatin", draft.name)
            assertEquals("20mg", draft.dosage)
            assertEquals("with food", draft.instructions)
            assertEquals(dailyTimes(listOf("08:00")), draft.schedule)
            assertTrue(saved().isEmpty())
        }

    @Test
    fun `a blank phrase cannot be reviewed`() =
        withUi(NOW) {
            val vm = vm()
            assertFalse(vm.state.value.canRead)

            vm.setPhrase("   ")
            assertFalse(vm.state.value.canRead)

            vm.setPhrase("call pharmacy friday 2pm")
            assertTrue(vm.state.value.canRead)
        }

    @Test
    fun `an example fills the phrase box`() =
        withUi(NOW) {
            val vm = vm()

            vm.usePhrase("call pharmacy friday 2pm")

            assertEquals("call pharmacy friday 2pm", vm.state.value.phrase)
        }

    @Test
    fun `a phrase the parser cannot read still opens a form, and says it did not understand`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("9am")

            vm.read()

            val state = vm.state.value
            assertFalse(state.understood)
            val draft = assertNotNull(state.draft)
            assertEquals("9am", draft.name)
            assertEquals(noneTimes(), draft.schedule)
            // Fillable from here: give it a name and it is savable.
            vm.setName("Call the dentist")
            assertTrue(vm.state.value.canSave)
        }

    @Test
    fun `the clock is read per press, so an every-N-hours phrase starts now`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("ibuprofen every 8 hours")

            vm.read()
            assertEquals(intervalTimes(8, NOW), vm.state.value.draft?.schedule)

            now = NOW + 600_000
            vm.read()
            assertEquals(intervalTimes(8, NOW + 600_000), vm.state.value.draft?.schedule)
        }

    @Test
    fun `the supply boxes open outright when the phrase named a count and stay behind a tap when it did not`() =
        withUi(NOW) {
            val withSupply = vm()
            withSupply.setPhrase("metformin 500mg twice daily 30 pills")
            withSupply.read()
            val without = vm()
            without.setPhrase("atorvastatin 20mg every morning")
            without.read()

            assertTrue(withSupply.state.value.supplyOpen)
            assertEquals(30.0, withSupply.state.value.draft?.pillsRemaining)
            assertFalse(without.state.value.supplyOpen)

            without.openSupply()
            assertTrue(without.state.value.supplyOpen)
        }

    @Test
    fun `names supply when any one of the three counts is there`() =
        withUi(NOW) {
            val vm = vm().also { it.setPhrase("metformin 500mg twice daily"); it.read() }
            val base = assertNotNull(vm.state.value.draft)

            assertFalse(namesSupply(base))
            assertTrue(namesSupply(base.copy(pillsRemaining = 1.0)))
            assertTrue(namesSupply(base.copy(pillsPerDose = 1.0)))
            assertTrue(namesSupply(base.copy(refillReminderDays = 7)))
        }

    @Test
    fun `correcting a medication to a task drops the dose, the supply and the drug identity`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("metformin 500mg twice daily 30 pills")
            vm.read()

            vm.setKind(ReminderKind.Task)

            val draft = assertNotNull(vm.state.value.draft)
            assertEquals(ReminderKind.Task, draft.kind)
            assertNull(draft.dosage)
            assertNull(draft.pillsRemaining)
            assertNull(draft.rxcui)
            assertFalse(vm.state.value.supplyOpen)
            assertEquals("metformin", draft.name)
        }

    @Test
    fun `correcting back to a medication keeps what the draft still has`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("call pharmacy friday 2pm")
            vm.read()

            vm.setKind(ReminderKind.Medication)

            assertEquals(ReminderKind.Medication, vm.state.value.draft?.kind)
        }

    @Test
    fun `every field of the draft can be corrected in place`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("atorvastatin 20mg every morning")
            vm.read()

            vm.setName("Lipitor")
            vm.setDosage("40mg")
            vm.setInstructions("at night")
            vm.setPriority(Priority.High)
            vm.setSchedule(dailyTimes(listOf("21:00")))
            vm.setPillsRemaining(10.0)
            vm.setPillsPerDose(2.0)
            vm.setRefillReminderDays(5)

            val draft = assertNotNull(vm.state.value.draft)
            assertEquals("Lipitor", draft.name)
            assertEquals("40mg", draft.dosage)
            assertEquals("at night", draft.instructions)
            assertEquals(Priority.High, draft.priority)
            assertEquals(dailyTimes(listOf("21:00")), draft.schedule)
            assertEquals(10.0, draft.pillsRemaining)
            assertEquals(2.0, draft.pillsPerDose)
            assertEquals(5, draft.refillReminderDays)
        }

    @Test
    fun `clearing an optional field removes it rather than storing blank text`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("atorvastatin 20mg every morning with food")
            vm.read()

            vm.setDosage("  ")
            vm.setInstructions("")

            assertNull(vm.state.value.draft?.dosage)
            assertNull(vm.state.value.draft?.instructions)
        }

    @Test
    fun `editing before there is a draft is harmless`() =
        withUi(NOW) {
            val vm = vm()

            vm.setName("x")
            vm.setKind(ReminderKind.Task)

            assertNull(vm.state.value.draft)
        }

    @Test
    fun `back to the phrase keeps what was typed`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("call pharmacy friday 2pm")
            vm.read()

            vm.editPhrase()

            assertNull(vm.state.value.draft)
            assertEquals("call pharmacy friday 2pm", vm.state.value.phrase)
        }

    @Test
    fun `saving writes the reviewed draft, trimmed, then reschedules and confirms`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("atorvastatin 20mg every morning with food")
            vm.read()
            vm.setName("  Atorvastatin ")
            vm.setPriority(Priority.Critical)

            vm.save().join()

            val row = saved().single()
            assertEquals("Atorvastatin", row.name)
            assertEquals(ReminderKind.Medication, row.kind)
            assertEquals("20mg", row.dosage)
            assertEquals("with food", row.instructions)
            assertEquals(Priority.Critical, row.priority)
            assertEquals(dailyTimes(listOf("08:00")), row.schedule)
            assertEquals(USER, row.userId)
            assertTrue(vm.state.value.saved)
            assertEquals("Medication added" to ToastKind.Success, container.toaster.current.value?.let { it.message to it.kind })
        }

    @Test
    fun `optional text is trimmed at the boundary, not on every keystroke`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("atorvastatin every morning")
            vm.read()
            vm.setDosage(" 40mg ")
            vm.setInstructions("  at night ")
            assertEquals(" 40mg ", vm.state.value.draft?.dosage, "a space typed between words must survive typing")

            vm.save().join()

            val row = saved().single()
            assertEquals("40mg", row.dosage)
            assertEquals("at night", row.instructions)
        }

    @Test
    fun `saving arms the new reminder`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("atorvastatin 20mg every morning")
            vm.read()

            vm.save().join()

            val alarms =
                org.robolectric.Shadows.shadowOf(
                    androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
                        .getSystemService(android.app.AlarmManager::class.java),
                ).scheduledAlarms
            assertTrue(alarms.isNotEmpty(), "a write must be followed by a reschedule")
        }

    @Test
    fun `a task is saved as a task with no dose or supply`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("call pharmacy friday 2pm")
            vm.read()

            vm.save().join()

            val row = saved().single()
            assertEquals(ReminderKind.Task, row.kind)
            assertNull(row.dosage)
            assertNull(row.pillsRemaining)
            assertEquals("Task added", container.toaster.current.value?.message)
        }

    @Test
    fun `a draft with no name is not savable, and a second tap cannot write twice`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("9am")
            vm.read()
            vm.setName("   ")
            assertFalse(vm.state.value.canSave)
            vm.save().join()
            assertTrue(saved().isEmpty())

            vm.setName("Call the dentist")
            vm.save().join()
            vm.save().join()
            assertEquals(1, saved().size)
            assertFalse(vm.state.value.canSave)
        }

    @Test
    fun `a failed save keeps the draft, says so, and can be tried again`() =
        withUi(NOW) {
            val vm = vm()
            vm.setPhrase("call pharmacy friday 2pm")
            vm.read()
            database.openHelper.writableDatabase.execSQL("ALTER TABLE reminders RENAME TO reminders_gone")

            vm.save().join()

            val state = vm.state.value
            assertNotNull(state.error)
            assertFalse(state.saved)
            assertFalse(state.saving)
            assertNotNull(state.draft)
            assertTrue(state.canSave)
            assertEquals(ToastKind.Error, container.toaster.current.value?.kind)
            assertEquals("Couldn't save — will retry on next sync", container.toaster.current.value?.message)
        }

    @Test
    fun `saving with no draft does nothing`() =
        withUi(NOW) {
            vm().save().join()

            assertTrue(saved().isEmpty())
            assertNull(container.toaster.current.value)
        }

    @Test
    fun `the wording is per Kind and priorities say what could be typed`() {
        assertEquals("Save medication", copyFor(ReminderKind.Medication).saveButton)
        assertEquals("Save task", copyFor(ReminderKind.Task).saveButton)
        assertEquals("Instructions", copyFor(ReminderKind.Medication).notesLabel)
        assertEquals("Notes", copyFor(ReminderKind.Task).notesLabel)
        assertEquals("Medication added", copyFor(ReminderKind.Medication).savedToast)
        assertEquals("Task added", copyFor(ReminderKind.Task).savedToast)
        assertEquals(listOf("Urgent", "Important", "Normal", "Low"), Priority.entries.map(::priorityLabel))
    }
}
