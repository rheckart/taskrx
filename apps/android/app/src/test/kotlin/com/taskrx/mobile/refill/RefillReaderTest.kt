package com.taskrx.mobile.refill

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.refill.RefillProjection
import com.taskrx.core.refill.UnprojectableReason
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.SyncOperation
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.data.Change
import com.taskrx.mobile.withFixture
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Executable coverage for the Refill read (archive/expo-rn:apps/mobile/src/refill/read.test.ts).
 *
 * :core runs every rule about how long a supply lasts and what gets raised. What it cannot
 * reach is what the ticket is about: that the Backlog ends up agreeing with the projection,
 * once, and that collecting a prescription puts it back. The assertions read the Reminders
 * that EXIST afterwards.
 */

/** An hour before the first dose of the day, so today's dose is still to come. */
private val NOW = LocalDateTime.of(2026, 8, 13, 8, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class RefillReaderTest {
    private suspend fun Fixture.medication(
        id: String = "med-1",
        name: String = "Atorvastatin",
        schedule: ScheduleTimes = dailyTimes(listOf("09:00")),
        pillsRemaining: Double? = null,
        pillsPerDose: Double? = null,
        refillReminderDays: Int? = null,
    ) = reminder(id, ReminderKind.Medication, schedule, name = name) {
        it.copy(pillsRemaining = pillsRemaining, pillsPerDose = pillsPerDose, refillReminderDays = refillReminderDays)
    }

    private suspend fun Fixture.setSupply(id: String, pills: Double) {
        container.reminders.update(id, UpdateReminderPatch(pillsRemaining = Change.To(pills)))
    }

    /** The tasks standing in the Backlog after the read, by name. */
    private suspend fun Fixture.backlogNames() = container.reminders.list(USER, ReminderKind.Task).map { it.name }

    private suspend fun Fixture.read() = container.refills.read(USER, NOW)

    @Test
    fun `reports how long each medication has left`() =
        withFixture(NOW) {
            medication(pillsRemaining = 12.0, pillsPerDose = 1.0)

            val projection = assertIs<RefillProjection.RefillOutlook>(read().rows.single().projection)

            assertEquals(12, projection.occurrencesRemaining)
            assertEquals(12, projection.daysRemaining)
        }

    @Test
    fun `says nothing about the supply of a medication that has none recorded`() =
        withFixture(NOW) {
            medication(pillsPerDose = 1.0)

            // Not a zero and not a run-out date: an unrecorded supply is the absence of a claim.
            assertEquals(RefillProjection.Unprojectable(UnprojectableReason.NoSupply), read().rows.single().projection)
        }

    @Test
    fun `puts the medication closest to running out first`() =
        withFixture(NOW) {
            medication("m1", "Plenty", pillsRemaining = 40.0, pillsPerDose = 1.0)
            medication("m2", "Soon", pillsRemaining = 2.0, pillsPerDose = 1.0)
            medication("m3", "Untracked")
            medication("m4", "Middling", pillsRemaining = 9.0, pillsPerDose = 1.0)

            // Untracked last: "nothing recorded" is not urgent and a missing number is not a zero.
            assertEquals(listOf("Soon", "Middling", "Plenty", "Untracked"), read().rows.map { it.medication.name })
        }

    @Test
    fun `raises a Refill Reminder into the Backlog before the supply runs out`() =
        withFixture(NOW) {
            medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)

            val reading = read()

            assertEquals(listOf("Refill Atorvastatin"), reading.raised)
            assertEquals(listOf("Refill Atorvastatin"), backlogNames())
        }

    @Test
    fun `leaves the Backlog alone while the supply is comfortable`() =
        withFixture(NOW) {
            medication(pillsRemaining = 60.0, pillsPerDose = 1.0, refillReminderDays = 7)

            read()

            assertEquals(emptyList(), backlogNames())
        }

    @Test
    fun `does not raise a second Reminder for a medication already warned about`() =
        withFixture(NOW) {
            medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)

            read()
            val second = read()

            assertEquals(emptyList(), second.raised)
            assertEquals(listOf("Refill Atorvastatin"), backlogNames())
        }

    @Test
    fun `retires the Reminder once the supply has been topped up`() =
        withFixture(NOW) {
            medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)
            read()

            setSupply("med-1", 30.0)
            val afterCollecting = read()

            assertEquals(listOf("Refill Atorvastatin"), afterCollecting.retired)
            assertEquals(emptyList(), backlogNames())
            val projection = assertIs<RefillProjection.RefillOutlook>(afterCollecting.rows.single().projection)
            assertEquals(30, projection.daysRemaining)
            assertEquals(false, projection.needsRefill)
        }

    @Test
    fun `raises again when a topped-up medication runs low a second time`() =
        withFixture(NOW) {
            medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)
            read()
            setSupply("med-1", 30.0)
            read()

            setSupply("med-1", 2.0)
            val runningLowAgain = read()

            // Only true because a withdrawn warning is not read back as a standing one.
            assertEquals(listOf("Refill Atorvastatin"), runningLowAgain.raised)
        }

    /**
     * The withdrawal is decided from a medication the screen must not list, so the read has
     * to fetch rows it then filters out. Fetching only the active ones would leave the
     * warning standing forever.
     */
    @Test
    fun `withdraws the warning for a stopped medication without listing it`() =
        withFixture(NOW) {
            medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)
            read()
            assertEquals(listOf("Refill Atorvastatin"), backlogNames())

            container.reminders.softDelete("med-1")
            val afterStopping = read()

            assertEquals(listOf("Refill Atorvastatin"), afterStopping.retired)
            assertEquals(emptyList(), backlogNames())
            assertEquals(emptyList(), afterStopping.rows)
        }

    @Test
    fun `has nothing to say about tasks`() =
        withFixture(NOW) {
            reminder("t1", ReminderKind.Task, noneTimes(), name = "Buy milk")
            reminder("t2", ReminderKind.Task, noneTimes(), name = "Call the surgery")

            val reading = read()

            assertEquals(RefillReading(emptyList(), emptyList(), emptyList()), reading)
        }

    @Test
    fun `writes the Reminder the planner decided on, id and all, and queues it for sync`() =
        withFixture(NOW) {
            medication(pillsRemaining = 0.0, pillsPerDose = 1.0, refillReminderDays = 7)
            val refills = RefillReader(container.reminders, mintId = { "generated-id" })
            val before = container.syncQueue.pending().size

            refills.read(USER, NOW)

            val raised = container.reminders.get("generated-id")!!
            assertEquals(ReminderKind.Task, raised.kind)
            assertEquals(noneTimes(), raised.schedule)
            assertEquals(Priority.High, raised.priority)
            val queued = container.syncQueue.pending().drop(before)
            assertEquals(listOf(SyncOperation.Create), queued.map { it.operation })
            assertEquals("generated-id", queued.single().entityId)
        }

    @Test
    fun `retiring a warning stops it rather than deleting it, and queues the delete`() =
        withFixture(NOW) {
            medication(pillsRemaining = 3.0, pillsPerDose = 1.0, refillReminderDays = 7)
            read()
            setSupply("med-1", 30.0)
            val before = container.syncQueue.pending().size

            read()

            val stopped = container.reminders.list(USER, ReminderKind.Task, includeInactive = true).single()
            assertEquals(false, stopped.active)
            assertTrue(container.syncQueue.pending().drop(before).any { it.operation == SyncOperation.Delete })
        }

    @Test
    fun `reports nothing rather than failing for someone with no medications`() =
        withFixture(NOW) {
            assertEquals(RefillReading(emptyList(), emptyList(), emptyList()), read())
        }
}
