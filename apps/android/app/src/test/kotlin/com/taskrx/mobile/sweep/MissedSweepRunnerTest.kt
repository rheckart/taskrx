package com.taskrx.mobile.sweep

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.DAY
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.SyncOperation
import com.taskrx.mobile.withFixture
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Executable coverage for the sweep's persistence pass (archive/expo-rn:apps/mobile/src/sweep/runner.test.ts).
 *
 * The planner's rules are tested in :core; this covers what nothing there can reach: which
 * of the two writes a verdict becomes, and whether two foregrounds arriving together can
 * mint one moment twice. Storage is real (in-memory Room), so the assertions read what
 * was stored.
 */
private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class MissedSweepRunnerTest {
    /** A Reminder due once at [at], created well before it so the planner may judge it. */
    private suspend fun Fixture.dueAt(id: String, at: Long, kind: ReminderKind = ReminderKind.Medication): Reminder =
        reminder(id, kind, onceTimes(at), createdAt = NOW - 10 * DAY)

    private suspend fun Fixture.stored(): List<ReminderInstance> = container.instances.list(InstanceFilter(USER))

    @Test
    fun `records a moment nobody answered, which had no row at all until now`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)

            val swept = container.sweep.run(USER, NOW)

            assertEquals(1, swept)
            val row = stored().single()
            assertEquals("r1", row.reminderId)
            assertEquals(NOW - 3 * HOUR, row.scheduledTime)
            assertEquals(InstanceStatus.Missed, row.status)
            assertEquals(ReminderKind.Medication, row.reminderKind)
        }

    @Test
    fun `a minted row carries the Kind of its Reminder`() =
        withFixture(NOW) {
            dueAt("t1", NOW - 3 * HOUR, ReminderKind.Task)

            container.sweep.run(USER, NOW)

            assertEquals(ReminderKind.Task, stored().single().reminderKind)
        }

    @Test
    fun `settles the row that already exists rather than opening a second one`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)
            val pending = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, NOW - 3 * HOUR))

            container.sweep.run(USER, NOW)

            val row = stored().single()
            assertEquals(pending.id, row.id)
            assertEquals(InstanceStatus.Missed, row.status)
        }

    @Test
    fun `leaves an answered moment alone, and does not mint behind it`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)
            val answered = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, NOW - 3 * HOUR))
            container.instances.markCompleted(answered.id, NOW - 3 * HOUR + 1)

            assertEquals(0, container.sweep.run(USER, NOW))

            assertEquals(InstanceStatus.Completed, stored().single().status)
        }

    @Test
    fun `writes nothing while a moment is still within its Grace Period`() =
        withFixture(NOW) {
            dueAt("r1", NOW - HOUR)

            assertEquals(0, container.sweep.run(USER, NOW))
            assertEquals(emptyList(), stored())
        }

    @Test
    fun `a second sweep after the first plans nothing`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)

            assertEquals(1, container.sweep.run(USER, NOW))
            assertEquals(0, container.sweep.run(USER, NOW))
            assertEquals(1, stored().size)
        }

    @Test
    fun `a stopped Reminder is not swept`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)
            container.reminders.softDelete("r1")

            assertEquals(0, container.sweep.run(USER, NOW))
        }

    @Test
    fun `each verdict is queued for sync as a create or an update`() =
        withFixture(NOW) {
            dueAt("minted", NOW - 3 * HOUR)
            dueAt("settled", NOW - 4 * HOUR)
            container.instances.create(CreateReminderInstanceInput(USER, "settled", ReminderKind.Medication, NOW - 4 * HOUR))
            val before = container.syncQueue.pending().size

            container.sweep.run(USER, NOW)

            val added = container.syncQueue.pending().drop(before)
            // Chronological: the older moment is the settle, the newer one the mint.
            assertEquals(listOf(SyncOperation.Update, SyncOperation.Create), added.map { it.operation })
        }

    /**
     * The point of the lock. The read is stretched so that, without serialization, both
     * sweeps would read "nothing stored" before either wrote.
     */
    @Test
    fun `does not record the same moment twice when two sweeps overlap`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)
            val slow = MissedSweepRunner(SlowRead(RoomSweepStore(container.reminders, container.instances)))

            val counts = coroutineScope { listOf(async { slow.run(USER, NOW) }, async { slow.run(USER, NOW) }).awaitAll() }

            assertEquals(1, stored().size)
            assertEquals(1, counts.sum())
        }

    @Test
    fun `keeps sweeping after one has failed, and the failure reaches its own caller`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)
            val inner = RoomSweepStore(container.reminders, container.instances)
            var failures = 1
            val flaky =
                MissedSweepRunner(
                    object : SweepStore by inner {
                        override suspend fun activeReminders(userId: String): List<Reminder> {
                            if (failures-- > 0) error("database is locked")
                            return inner.activeReminders(userId)
                        }
                    },
                )

            val failure = assertFailsWith<IllegalStateException> { flaky.run(USER, NOW) }
            assertEquals("database is locked", failure.message)

            assertEquals(1, flaky.run(USER, NOW))
        }

    @Test
    fun `an answer recorded between the sweep's read and its write is not overwritten`() =
        withFixture(NOW) {
            dueAt("r1", NOW - 3 * HOUR)
            val pending = container.instances.create(CreateReminderInstanceInput(USER, "r1", ReminderKind.Medication, NOW - 3 * HOUR))
            val inner = RoomSweepStore(container.reminders, container.instances)
            val racing =
                MissedSweepRunner(
                    object : SweepStore by inner {
                        override suspend fun instancesBetween(userId: String, since: Long, until: Long): List<ReminderInstance> =
                            inner.instancesBetween(userId, since, until).also {
                                container.instances.markCompleted(pending.id, NOW - 1)
                            }
                    },
                )

            racing.run(USER, NOW)

            val row = stored().single()
            assertEquals(InstanceStatus.Completed, row.status)
            assertEquals(NOW - 1, row.completedAt)
        }

    private class SlowRead(private val inner: SweepStore) : SweepStore by inner {
        override suspend fun instancesBetween(userId: String, since: Long, until: Long): List<ReminderInstance> =
            inner.instancesBetween(userId, since, until).also { delay(100) }
    }
}
