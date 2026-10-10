package com.taskrx.mobile.data

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

@RunWith(RobolectricTestRunner::class)
class ReminderInstancesRepositoryTest {
    private fun com.taskrx.mobile.Fixture.open(
        reminderId: String = "m1",
        scheduledTime: Long = T0,
        kind: ReminderKind = ReminderKind.Medication,
    ) = CreateReminderInstanceInput(USER, reminderId, kind, scheduledTime)

    @Test
    fun `create opens a pending Instance and enqueues a create`() =
        withFixture(T0) {
            reminder("m1")

            val created = container.instances.create(open())

            assertEquals(
                ReminderInstance(
                    id = created.id,
                    reminderId = "m1",
                    userId = USER,
                    reminderKind = ReminderKind.Medication,
                    scheduledTime = T0,
                    status = InstanceStatus.Pending,
                    completedAt = null,
                    snoozedUntil = null,
                    snoozeCount = 0,
                    notes = null,
                    createdAt = T0,
                    updatedAt = T0,
                ),
                created,
            )
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncEntityType.ReminderInstance, entry.entityType)
            assertEquals(created.id, entry.entityId)
            assertEquals(SyncOperation.Create, entry.operation)
            val payload = queuePayloads().last()
            assertEquals(
                setOf(
                    "localId", "userId", "reminderId", "reminderKind", "scheduledTime", "status", "completedAt",
                    "snoozedUntil", "snoozeCount", "notes", "createdAt", "updatedAt",
                ),
                payload.keys,
            )
            assertEquals(created.id, payload["localId"]!!.jsonPrimitive.content)
            assertEquals("m1", payload["reminderId"]!!.jsonPrimitive.content)
            assertEquals("medication", payload["reminderKind"]!!.jsonPrimitive.content)
            assertEquals(T0, payload["scheduledTime"]!!.jsonPrimitive.long)
            assertEquals("pending", payload["status"]!!.jsonPrimitive.content)
            assertEquals(0, payload["snoozeCount"]!!.jsonPrimitive.int)
            assertEquals(JsonNull, payload["completedAt"])
            assertEquals(JsonNull, payload["snoozedUntil"])
            assertEquals(JsonNull, payload["notes"])
        }

    @Test
    fun `create can open an already settled row`() =
        withFixture(T0) {
            reminder("m1")

            val missed = container.instances.create(open().copy(status = InstanceStatus.Missed))
            val completed =
                container.instances.create(open(scheduledTime = T0 + 1).copy(status = InstanceStatus.Completed, completedAt = T0 + 5))

            assertEquals(InstanceStatus.Missed, missed.status)
            assertEquals(InstanceStatus.Completed, completed.status)
            assertEquals(T0 + 5, completed.completedAt)
            assertEquals(T0 + 5, queuePayloads().last()["completedAt"]!!.jsonPrimitive.long)
            assertEquals("missed", queuePayloads().first { it["scheduledTime"]?.jsonPrimitive?.long == T0 }["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an Instance cannot be opened against a Reminder that does not exist`() =
        withFixture(T0) {
            assertFailsWith<Exception> { container.instances.create(open("ghost")) }
            assertEquals(0, container.syncQueue.pending().size)
        }

    @Test
    fun `completing records the moment and the notes, and enqueues the post-write state`() =
        withFixture(T0) {
            reminder("m1")
            val created = container.instances.create(open())
            now = T0 + 7

            val done = container.instances.markCompleted(created.id, completedAt = T0 + 5, notes = "with water")

            assertEquals(InstanceStatus.Completed, done.status)
            assertEquals(T0 + 5, done.completedAt)
            assertEquals("with water", done.notes)
            assertEquals(T0 + 7, done.updatedAt)
            assertEquals(T0, done.createdAt)
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Update, entry.operation)
            val payload = queuePayloads().last()
            assertEquals("completed", payload["status"]!!.jsonPrimitive.content)
            assertEquals(T0 + 5, payload["completedAt"]!!.jsonPrimitive.long)
            assertEquals("with water", payload["notes"]!!.jsonPrimitive.content)
            assertEquals(T0 + 7, payload["updatedAt"]!!.jsonPrimitive.long)
            assertEquals(T0, payload["createdAt"]!!.jsonPrimitive.long)
        }

    @Test
    fun `snoozing counts each snooze and is neither a skip nor a miss`() =
        withFixture(T0) {
            reminder("m1")
            val created = container.instances.create(open())

            val first = container.instances.markSnoozed(created.id, T0 + 600_000)
            val second = container.instances.markSnoozed(created.id, T0 + 1_200_000)

            assertEquals(InstanceStatus.Snoozed, first.status)
            assertEquals(1, first.snoozeCount)
            assertEquals(2, second.snoozeCount)
            assertEquals(T0 + 1_200_000, second.snoozedUntil)
            assertEquals(2, queuePayloads().last()["snoozeCount"]!!.jsonPrimitive.int)
            assertEquals(T0 + 1_200_000, queuePayloads().last()["snoozedUntil"]!!.jsonPrimitive.long)
        }

    @Test
    fun `an answer that follows a snooze clears the snooze time but keeps earlier notes`() =
        withFixture(T0) {
            reminder("m1")
            val created = container.instances.create(open())
            container.instances.markSkipped(created.id, "felt sick")
            container.instances.markSnoozed(created.id, T0 + 1)

            val back = container.instances.markCompleted(created.id, T0 + 9)

            assertNull(back.snoozedUntil)
            assertEquals("felt sick", back.notes)
            assertEquals(1, back.snoozeCount)
        }

    @Test
    fun `skipping and missing settle the row`() =
        withFixture(T0) {
            reminder("m1")
            val a = container.instances.create(open())
            val b = container.instances.create(open(scheduledTime = T0 + 1))

            assertEquals(InstanceStatus.Skipped, container.instances.markSkipped(a.id).status)
            assertEquals(InstanceStatus.Missed, container.instances.markMissed(b.id).status)
            assertEquals(listOf("skipped", "missed"), queuePayloads().takeLast(2).map { it["status"]!!.jsonPrimitive.content })
        }

    @Test
    fun `answering an unknown Instance fails and enqueues nothing`() =
        withFixture(T0) {
            assertFailsWith<IllegalStateException> { container.instances.markMissed("nope") }
            assertEquals(0, container.syncQueue.pending().size)
        }

    @Test
    fun `list filters on every axis and returns the newest moment first`() =
        withFixture(T0) {
            reminder("m1")
            reminder("t1", ReminderKind.Task)
            val a = container.instances.create(open(scheduledTime = T0 + 10))
            val b = container.instances.create(open(scheduledTime = T0 + 30))
            val c = container.instances.create(open("t1", T0 + 20, ReminderKind.Task).copy(status = InstanceStatus.Completed))
            database.reminderDao().insert(database.reminderDao().get("m1")!!.copy(id = "m2", userId = "other"))
            container.instances.create(CreateReminderInstanceInput("other", "m2", ReminderKind.Medication, T0 + 40))

            fun ids(filter: InstanceFilter) = container.instances.let { repo -> kotlinx.coroutines.runBlocking { repo.list(filter) } }.map { it.id }

            assertEquals(listOf(b.id, c.id, a.id), ids(InstanceFilter(USER)))
            assertEquals(listOf(b.id, a.id), ids(InstanceFilter(USER, reminderId = "m1")))
            assertEquals(listOf(c.id), ids(InstanceFilter(USER, reminderKind = ReminderKind.Task)))
            assertEquals(listOf(c.id, a.id), ids(InstanceFilter(USER, until = T0 + 20)))
            assertEquals(listOf(b.id, c.id), ids(InstanceFilter(USER, since = T0 + 20)))
            assertEquals(listOf(c.id), ids(InstanceFilter(USER, status = InstanceStatus.Completed)))
            assertEquals(listOf(c.id), ids(InstanceFilter(USER, since = T0 + 20, until = T0 + 20)))
        }

    @Test
    fun `pendingThrough returns pending rows up to the instant, oldest first`() =
        withFixture(T0) {
            reminder("m1")
            val late = container.instances.create(open(scheduledTime = T0 + 20))
            val early = container.instances.create(open(scheduledTime = T0 + 10))
            container.instances.create(open(scheduledTime = T0 + 5).copy(status = InstanceStatus.Completed))
            container.instances.create(open(scheduledTime = T0 + 50))

            assertEquals(listOf(early.id, late.id), container.instances.pendingThrough(USER, T0 + 20).map { it.id })
            assertTrue(container.instances.pendingThrough("someone-else", T0 + 20).isEmpty())
        }

    @Test
    fun `findAnswerableForOccurrence finds pending and snoozed rows but not settled ones`() =
        withFixture(T0) {
            reminder("m1")
            val row = container.instances.create(open())
            assertEquals(row.id, container.instances.findAnswerableForOccurrence(USER, "m1", T0)?.id)

            container.instances.markSnoozed(row.id, T0 + 1)
            assertEquals(row.id, container.instances.findAnswerableForOccurrence(USER, "m1", T0)?.id)

            container.instances.markCompleted(row.id, T0 + 2)
            assertNull(container.instances.findAnswerableForOccurrence(USER, "m1", T0))
            assertNull(container.instances.findAnswerableForOccurrence(USER, "m1", T0 + 99))
            assertNull(container.instances.findAnswerableForOccurrence(USER, "other", T0))
        }

    @Test
    fun `lastCompletedFor is the most recently completed row, or null`() =
        withFixture(T0) {
            reminder("m1")
            assertNull(container.instances.lastCompletedFor(USER, "m1"))
            val a = container.instances.create(open(scheduledTime = T0 + 1))
            val b = container.instances.create(open(scheduledTime = T0 + 2))
            container.instances.markCompleted(a.id, T0 + 100)
            container.instances.markCompleted(b.id, T0 + 50)
            container.instances.create(open(scheduledTime = T0 + 3))

            assertEquals(a.id, container.instances.lastCompletedFor(USER, "m1")?.id)
        }

    @Test
    fun `enqueueExternalWrite chooses the operation from convex_id and describes the whole row`() =
        withFixture(T0) {
            reminder("m1")
            val row = container.instances.create(open())
            val before = container.syncQueue.pending().size

            container.instances.enqueueExternalWrite(row.id)
            container.instances.setConvexId(row.id, "cvx-9")
            container.instances.enqueueExternalWrite(row.id)
            container.instances.enqueueExternalWrite("absent")

            val added = container.syncQueue.pending().drop(before)
            assertEquals(listOf(SyncOperation.Create, SyncOperation.Update), added.map { it.operation })
            assertEquals(row.id, queuePayloads().last()["localId"]!!.jsonPrimitive.content)
            assertEquals("cvx-9", container.instances.getConvexId(row.id))
            assertNull(container.instances.getConvexId("absent"))
        }

    @Test
    fun `reconcilePending re-enqueues a pending row that lost its queue entry, once`() =
        withFixture(T0) {
            reminder("m1")
            val lost = container.instances.create(open())
            val dead = container.instances.create(open(scheduledTime = T0 + 1))
            val live = container.instances.create(open(scheduledTime = T0 + 2))
            val queue = container.syncQueue.pending()
            container.syncQueue.remove(queue.first { it.entityId == lost.id }.id)
            container.syncQueue.markDeadLettered(queue.first { it.entityId == dead.id }.id)

            assertEquals(2, container.instances.reconcilePending())
            assertEquals(0, container.instances.reconcilePending())
            assertTrue(container.syncQueue.pending().any { it.entityId == live.id })
        }

    @Test
    fun `sync status moves between pending, synced and error`() =
        withFixture(T0) {
            reminder("m1")
            val row = container.instances.create(open())
            val dao = database.reminderInstanceDao()

            assertEquals("pending", dao.get(row.id)!!.syncStatus)
            container.instances.markSynced(row.id)
            assertEquals("synced", dao.get(row.id)!!.syncStatus)
            container.instances.markError(row.id, "boom")
            assertEquals("error", dao.get(row.id)!!.syncStatus)
            container.instances.markCompleted(row.id, T0)
            assertEquals("pending", dao.get(row.id)!!.syncStatus)
        }

    @Test
    fun `upsertMany writes pulled rows as synced with no enqueue, and updates in place`() =
        withFixture(T0) {
            reminder("m1")
            val pulled = com.taskrx.core.domain.createReminderInstance(
                id = "i1", reminderId = "m1", userId = USER, reminderKind = ReminderKind.Medication,
                scheduledTime = T0, createdAt = 1, updatedAt = 2, status = InstanceStatus.Completed,
                completedAt = 3, snoozedUntil = 4, snoozeCount = 2, notes = "n",
            )
            val before = container.syncQueue.pending().size

            assertEquals(1, container.instances.upsertMany(listOf(PulledReminderInstance(pulled, "cvx-1"))))

            assertEquals(pulled, container.instances.get("i1"))
            assertEquals("cvx-1", container.instances.getConvexId("i1"))
            assertEquals("synced", database.reminderInstanceDao().get("i1")!!.syncStatus)
            assertEquals(before, container.syncQueue.pending().size)

            container.instances.upsertMany(listOf(PulledReminderInstance(pulled.copy(notes = "edited"), "cvx-1")))
            assertEquals("edited", container.instances.get("i1")!!.notes)
            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
        }

    @Test
    fun `an unreadable stored status reads as pending rather than failing`() =
        withFixture(T0) {
            reminder("m1")
            val row = container.instances.create(open())
            database.reminderInstanceDao().update(database.reminderInstanceDao().get(row.id)!!.copy(status = "taken", reminderKind = "weird"))

            val read = container.instances.get(row.id)!!
            assertEquals(InstanceStatus.Pending, read.status)
            assertEquals(ReminderKind.Task, read.reminderKind)
        }
}
