package com.taskrx.mobile.undo

import android.app.AlarmManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.undo.UndoVerdict
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.withUi
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private val NOW = Instant.parse("2026-08-06T09:05:00Z").toEpochMilli()
private val NINE = Instant.parse("2026-08-06T09:00:00Z").toEpochMilli()

/**
 * Taking an answer back, as the two screens use it. Storage, AlarmManager and WorkManager are
 * real underneath, so "cancel that moment's snooze and reschedule" is observed, not assumed.
 */
@RunWith(RobolectricTestRunner::class)
class UndoAnswersTest {
    private suspend fun Fixture.medication() = reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NOW - 10 * DAY)

    private suspend fun Fixture.openRow(scheduledTime: Long = NINE) =
        container.instances.create(CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, scheduledTime))

    private suspend fun Fixture.stored() = container.instances.list(InstanceFilter(USER))

    private fun alarms() =
        shadowOf(ApplicationProvider.getApplicationContext<Application>().getSystemService(AlarmManager::class.java)).scheduledAlarms

    @Test
    fun `a take, a skip and a snooze each go back to pending`() =
        withUi(NOW) {
            medication()
            val taken = openRow(NINE)
            val skipped = openRow(NINE + DAY)
            val snoozed = openRow(NINE + 2 * DAY)
            container.instances.markCompleted(taken.id, NOW)
            container.instances.markSkipped(skipped.id)
            container.instances.markSnoozed(snoozed.id, NOW + HOUR)

            for (id in listOf(taken.id, skipped.id, snoozed.id)) {
                val result = assertIs<UndoResult.Reopened>(container.undo.undo(id))
                assertEquals(InstanceStatus.Pending, result.instance.status)
            }

            assertEquals(setOf(InstanceStatus.Pending), stored().map { it.status }.toSet())
        }

    @Test
    fun `it says when the moment is already past its grace period, and not when it is inside it`() =
        withUi(NOW) {
            medication()
            val recent = openRow(NINE)
            val old = openRow(NINE - DAY)
            container.instances.markSkipped(recent.id)
            container.instances.markSkipped(old.id)

            assertEquals(false, assertIs<UndoResult.Reopened>(container.undo.undo(recent.id)).willBeMissed)
            assertEquals(true, assertIs<UndoResult.Reopened>(container.undo.undo(old.id)).willBeMissed)
        }

    @Test
    fun `undoing a snooze cancels that moment's deferred reminder and forgets it`() =
        withUi(NOW) {
            medication()
            val row = openRow(NINE)
            val until = NOW + HOUR
            container.instances.markSnoozed(row.id, until)
            container.notifications.scheduler.snooze("med", NINE, until)
            assertTrue(alarms().any { it.triggerAtTime == until }, "the snooze was not armed")

            container.undo.undo(row.id)

            assertTrue(alarms().none { it.triggerAtTime == until }, "the snooze is still armed")
            assertTrue(container.notifications.snoozes.all().isEmpty())
        }

    @Test
    fun `undoing a snooze leaves another moment's snooze armed`() =
        withUi(NOW) {
            medication()
            val row = openRow(NINE)
            container.instances.markSnoozed(row.id, NOW + HOUR)
            container.notifications.scheduler.snooze("med", NINE, NOW + HOUR)
            container.notifications.scheduler.snooze("med", NINE + DAY, NOW + 2 * HOUR)

            container.undo.undo(row.id)

            assertEquals(listOf(NINE + DAY), container.notifications.snoozes.all().map { it.scheduledTime })
        }

    @Test
    fun `undoing a skip of a moment still ahead arms its own reminder again`() =
        withUi(NINE - HOUR) {
            reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")), createdAt = NINE - 10 * DAY)
            val row = container.instances.create(CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, NINE))
            container.instances.markSkipped(row.id)
            container.notifications.rescheduleAfterWrite(USER)
            assertTrue(alarms().none { it.triggerAtTime == NINE }, "a skipped moment must not ring")

            container.undo.undo(row.id)

            assertTrue(alarms().any { it.triggerAtTime == NINE }, "the reopened moment was not armed again")
        }

    @Test
    fun `a moment the schedule does not predict is refused, and nothing changes`() =
        withUi(NOW) {
            medication()
            val extra = container.instances.create(CreateReminderInstanceInput(USER, "med", ReminderKind.Medication, NINE + 5 * HOUR + 30 * 60_000L))
            container.instances.markCompleted(extra.id, NOW)
            val queued = container.syncQueue.pending().size

            val result = container.undo.undo(extra.id)

            assertEquals(UndoResult.Refused(UndoVerdict.NotScheduled), result)
            assertEquals(InstanceStatus.Completed, container.instances.get(extra.id)!!.status)
            assertEquals(queued, container.syncQueue.pending().size)
        }

    @Test
    fun `an answer is refused when the moment was opened again beside it, so there are never two open rows`() =
        withUi(NOW) {
            medication()
            val answered = openRow(NINE)
            container.instances.markSkipped(answered.id)
            openRow(NINE) // the answer screen reopened the same moment (issue #21)

            val result = container.undo.undo(answered.id)

            assertEquals(UndoResult.Refused(UndoVerdict.MomentOpenAgain), result)
            assertEquals(InstanceStatus.Skipped, container.instances.get(answered.id)!!.status)
        }

    @Test
    fun `a missed row of the same moment also blocks it, since the sweep would count two misses`() =
        withUi(NOW) {
            medication()
            val answered = openRow(NINE)
            container.instances.markSkipped(answered.id)
            val other = openRow(NINE)
            container.instances.markMissed(other.id)

            assertEquals(UndoResult.Refused(UndoVerdict.MomentOpenAgain), container.undo.undo(answered.id))
        }

    @Test
    fun `an answer on another moment of the same reminder does not block it`() =
        withUi(NOW) {
            medication()
            val answered = openRow(NINE)
            container.instances.markSkipped(answered.id)
            openRow(NINE + DAY)

            assertIs<UndoResult.Reopened>(container.undo.undo(answered.id))
        }

    @Test
    fun `a pending or missed row has nothing to undo`() =
        withUi(NOW) {
            medication()
            val pending = openRow(NINE)
            val missed = openRow(NINE + DAY)
            container.instances.markMissed(missed.id)

            assertEquals(UndoResult.Refused(UndoVerdict.NotAnAnswer), container.undo.undo(pending.id))
            assertEquals(UndoResult.Refused(UndoVerdict.NotAnAnswer), container.undo.undo(missed.id))
        }

    @Test
    fun `an Instance that is not there is not found`() =
        withUi(NOW) {
            assertEquals(UndoResult.NotFound, container.undo.undo("nope"))
        }

    @Test
    fun `an Instance whose Reminder is gone is not found`() =
        withUi(NOW) {
            medication()
            val row = openRow(NINE)
            container.instances.markSkipped(row.id)
            database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
            database.openHelper.writableDatabase.execSQL("DELETE FROM reminders WHERE id = 'med'")

            assertEquals(UndoResult.NotFound, container.undo.undo(row.id))
        }

    @Test
    fun `a failure to cancel the snooze or to reschedule is logged and does not undo the undo`() =
        withUi(NOW) {
            medication()
            val row = openRow(NINE)
            container.instances.markSkipped(row.id)
            val logged = mutableListOf<String>()
            val undo =
                UndoAnswers(
                    container.reminders,
                    container.instances,
                    { NOW },
                    { USER },
                    cancelSnooze = { _, _ -> error("no alarm service") },
                    rescheduleAfterWrite = { error("no scheduler") },
                    log = { what, _ -> logged += what },
                )

            val result = undo.undo(row.id)

            assertIs<UndoResult.Reopened>(result)
            assertEquals(InstanceStatus.Pending, container.instances.get(row.id)!!.status)
            assertEquals(2, logged.size)
            assertNotNull(logged.firstOrNull { "snooze" in it })
            assertNotNull(logged.firstOrNull { "reschedule" in it })
        }
}
