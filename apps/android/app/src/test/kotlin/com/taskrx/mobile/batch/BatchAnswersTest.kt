package com.taskrx.mobile.batch

import com.taskrx.core.batch.BatchAction
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.dailyTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.ui.withUi
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val MIDNIGHT = Instant.parse("2026-08-06T00:00:00Z").toEpochMilli()
private val NINE = MIDNIGHT + 9 * HOUR

@RunWith(RobolectricTestRunner::class)
class BatchAnswersTest {
    private suspend fun Fixture.threeDoses(): List<String> {
        for (id in listOf("a", "b", "c")) reminder(id, schedule = dailyTimes(listOf("09:00")), createdAt = MIDNIGHT - 10 * DAY)
        return listOf("a", "b", "c")
    }

    private suspend fun Fixture.instances() = container.instances.list(InstanceFilter(USER))

    @Test
    fun `take all completes every dose at the moment, with one timestamp`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()

            val result = container.batch.answer(BatchAction.Take, NINE, ids)

            assertEquals(BatchResult(changed = 3, failed = 0), result)
            val rows = instances()
            assertEquals(3, rows.size)
            assertEquals(setOf(InstanceStatus.Completed), rows.map { it.status }.toSet())
            assertEquals(setOf(now), rows.map { it.completedAt }.toSet())
        }

    @Test
    fun `skip all skips every dose`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()

            container.batch.answer(BatchAction.Skip, NINE, ids)

            assertEquals(setOf(InstanceStatus.Skipped), instances().map { it.status }.toSet())
        }

    @Test
    fun `snooze all defers every dose to one instant and arms each`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()

            container.batch.answer(BatchAction.Snooze, NINE, ids)

            val rows = instances()
            assertEquals(setOf(InstanceStatus.Snoozed), rows.map { it.status }.toSet())
            assertEquals(setOf(now + 15 * 60_000), rows.map { it.snoozedUntil }.toSet())
            assertEquals(ids.toSet(), container.notifications.snoozes.all().map { it.reminderId }.toSet())
        }

    @Test
    fun `snooze all follows the length chosen in Settings, read when it is pressed`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            container.settings.setSnoozeMinutes(30)

            container.batch.answer(BatchAction.Snooze, NINE, ids)

            assertEquals(setOf(now + 30 * 60_000), instances().map { it.snoozedUntil }.toSet())
        }

    @Test
    fun `a dose already answered is left alone, not answered twice`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            val already = container.instances.create(com.taskrx.mobile.data.CreateReminderInstanceInput(USER, "a", com.taskrx.core.domain.ReminderKind.Medication, NINE))
            container.instances.markCompleted(already.id, NINE + 60_000)

            val result = container.batch.answer(BatchAction.Take, NINE, ids)

            assertEquals(BatchResult(changed = 2, failed = 0), result)
            val forA = instances().filter { it.reminderId == "a" }
            assertEquals(1, forA.size)
            assertEquals(NINE + 60_000, forA.single().completedAt)
        }

    @Test
    fun `a skipped dose is not taken by a later take all`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            container.batch.answer(BatchAction.Skip, NINE, listOf("a"))

            container.batch.answer(BatchAction.Take, NINE, ids)

            assertEquals(InstanceStatus.Skipped, instances().single { it.reminderId == "a" }.status)
            assertEquals(3, instances().size)
        }

    @Test
    fun `a snoozed dose is answered in place, not given a second row`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            container.batch.answer(BatchAction.Snooze, NINE, ids)

            container.batch.answer(BatchAction.Take, NINE, ids)

            val rows = instances()
            assertEquals(3, rows.size)
            assertEquals(setOf(InstanceStatus.Completed), rows.map { it.status }.toSet())
        }

    @Test
    fun `a reminder deleted since is skipped and the rest are answered`() =
        withUi(NINE + 5 * 60_000) {
            threeDoses()

            val result = container.batch.answer(BatchAction.Take, NINE, listOf("a", "gone", "b"))

            assertEquals(BatchResult(changed = 2, failed = 0), result)
            assertNull(instances().firstOrNull { it.reminderId == "gone" })
        }

    @Test
    fun `the same reminder listed twice is answered once`() =
        withUi(NINE + 5 * 60_000) {
            threeDoses()

            // Snooze is the Action a repeat would not be caught by: a snoozed dose is still answerable.
            val result = container.batch.answer(BatchAction.Snooze, NINE, listOf("a", "a"))

            assertEquals(1, result.changed)
            assertEquals(1, instances().size)
        }

    @Test
    fun `the schedule is re-read once for the batch and the shade is cleared`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            var reschedules = 0
            val dismissed = mutableListOf<Pair<Long, List<String>>>()
            val batch = batch(reschedule = { reschedules++ }, dismiss = { t, r -> dismissed += t to r })

            batch.answer(BatchAction.Take, NINE, ids)

            assertEquals(1, reschedules)
            assertEquals(listOf(NINE to ids), dismissed)
        }

    @Test
    fun `when the writes fail it says so and leaves the shade alone`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            var dismissed = false
            val logged = mutableListOf<String>()
            val batch = batch(dismiss = { _, _ -> dismissed = true }, log = { what, _ -> logged += what })
            database.openHelper.writableDatabase.execSQL("DROP TABLE reminder_instances")

            val result = batch.answer(BatchAction.Take, NINE, ids)

            assertEquals(BatchResult(changed = 0, failed = 3), result)
            assertEquals(3, logged.size)
            assertTrue(!dismissed)
        }

    @Test
    fun `a snooze that cannot be armed does not undo the answer`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            val logged = mutableListOf<String>()
            val batch = batch(arm = { _, _, _ -> error("no alarm") }, log = { what, _ -> logged += what })

            val result = batch.answer(BatchAction.Snooze, NINE, ids)

            assertEquals(BatchResult(3, 0), result)
            assertEquals(3, logged.size)
            assertEquals(setOf(InstanceStatus.Snoozed), instances().map { it.status }.toSet())
        }

    @Test
    fun `a reschedule that fails does not read as a failed answer`() =
        withUi(NINE + 5 * 60_000) {
            val ids = threeDoses()
            val logged = mutableListOf<String>()
            val batch = batch(reschedule = { error("boom") }, log = { what, _ -> logged += what })

            val result = batch.answer(BatchAction.Take, NINE, ids)

            assertEquals(BatchResult(3, 0), result)
            assertEquals(listOf("reschedule after a batch failed"), logged)
        }

    private fun Fixture.batch(
        arm: suspend (String, Long, Long) -> Boolean = { _, _, _ -> true },
        reschedule: suspend (String) -> Unit = {},
        dismiss: (Long, List<String>) -> Unit = { _, _ -> },
        log: (String, Throwable) -> Unit = { _, _ -> },
    ) = BatchAnswers(container.reminders, container.instances, { now }, { container.settings.snoozeMinutes.value }, { USER }, arm, reschedule, dismiss, log)
}
