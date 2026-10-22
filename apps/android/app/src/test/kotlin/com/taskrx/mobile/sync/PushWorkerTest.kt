package com.taskrx.mobile.sync

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.sync.SyncWorkerStatus
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.SyncEntityType
import com.taskrx.mobile.data.SyncOperation
import com.taskrx.mobile.data.UpdateReminderPatch
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

/** Mirrors archive/expo-rn:apps/mobile/src/sync/worker.test.ts, against a scripted gateway and real storage. */
@RunWith(RobolectricTestRunner::class)
class PushWorkerTest {
    private class Rig(val fx: Fixture, val scope: CoroutineScope, user: String? = USER) {
        val gateway = FakeGateway()
        val connectivity = FakeConnectivity()
        val pauses = mutableListOf<Long>()
        var user: String? = user
        val worker =
            PushWorker(fx.container.syncQueue, fx.container.reminders, fx.container.instances, gateway, connectivity, { this.user }, { pauses += it })
    }

    private fun rig(block: suspend Rig.() -> Unit) = withFixture(T0) { coroutineScope { Rig(this@withFixture, this).block() } }

    /** The drain's storage work runs off-thread, so a yield is not enough to reach the gateway. */
    private suspend fun Rig.awaitCall() {
        while (gateway.calls.isEmpty()) delay(5)
    }

    private fun Fixture.forgetServerIds() {
        database.openHelper.writableDatabase.execSQL("UPDATE reminders SET convex_id = NULL")
        database.openHelper.writableDatabase.execSQL("UPDATE reminder_instances SET convex_id = NULL")
    }

    private suspend fun Fixture.entries() = container.syncQueue.pending()

    private suspend fun Fixture.reminderRow(id: String) = database.reminderDao().get(id)!!

    private suspend fun Fixture.instanceRow(id: String) = database.reminderInstanceDao().get(id)!!

    // ---- drain control flow -------------------------------------------------------------

    @Test
    fun `reaches the queue and reports idle when connected`() =
        rig {
            fx.reminder("m1")
            worker.drain()
            assertEquals(1, gateway.mutations.size)
            assertEquals(SyncWorkerStatus.Idle, worker.status.value)
        }

    @Test
    fun `releases the guard after an offline drain`() =
        rig {
            fx.reminder("m1")
            connectivity.connected.value = false
            worker.drain()
            assertEquals(SyncWorkerStatus.Offline, worker.status.value)
            assertTrue(gateway.calls.isEmpty())

            connectivity.connected.value = true
            worker.drain()
            assertEquals(1, gateway.mutations.size)
        }

    @Test
    fun `releases the guard when the connectivity check throws`() =
        rig {
            fx.reminder("m1")
            connectivity.failCheck = IllegalStateException("no radio")
            worker.drain()
            assertEquals(SyncWorkerStatus.Error, worker.status.value)

            connectivity.failCheck = null
            worker.drain()
            assertEquals(1, gateway.mutations.size)
            assertEquals(SyncWorkerStatus.Idle, worker.status.value)
        }

    @Test
    fun `releases the guard when the drain body throws`() =
        rig {
            fx.reminder("m1")
            fx.database.openHelper.writableDatabase.execSQL("DROP TABLE sync_queue")
            worker.drain()
            assertEquals(SyncWorkerStatus.Error, worker.status.value)

            worker.markOffline()
            worker.drain() // reaches the body again, so the guard was released
            assertEquals(SyncWorkerStatus.Error, worker.status.value)
        }

    @Test
    fun `does not drain concurrently`() =
        rig {
            fx.reminder("m1")
            val gate = CompletableDeferred<Unit>()
            gateway.onMutation = { _, args ->
                gate.await()
                JsonPrimitive("cid-" + args["localId"])
            }
            val first = scope.launch { worker.drain() }
            awaitCall()
            worker.drain() // returns at once: one is running
            assertEquals(1, gateway.mutations.size)
            gate.complete(Unit)
            first.join()
            assertEquals(1, gateway.mutations.size)
        }

    @Test
    fun `a cancelled drain is not an error and does not wedge the next one`() =
        rig {
            fx.reminder("m1")
            val gate = CompletableDeferred<Unit>()
            gateway.onMutation = { _, _ ->
                gate.await()
                JsonPrimitive("never")
            }
            val job = scope.launch { worker.drain() }
            awaitCall()
            assertEquals(SyncWorkerStatus.Syncing, worker.status.value)
            job.cancel()
            job.join()
            assertEquals(SyncWorkerStatus.Idle, worker.status.value)

            gateway.onMutation = { _, args -> JsonPrimitive("cid-" + args["localId"]) }
            worker.drain()
            assertTrue(fx.entries().isEmpty())
        }

    @Test
    fun `signed out there is nothing to push to`() =
        rig {
            user = null
            fx.reminder("m1")
            worker.drain()
            assertTrue(gateway.calls.isEmpty())
            assertEquals(0, worker.pendingCount())
            assertEquals(1, fx.entries().size)
        }

    @Test
    fun `the pause markOffline sets is cleared by the next drain`() =
        rig {
            worker.markOffline()
            assertEquals(SyncWorkerStatus.Offline, worker.status.value)
            worker.drain()
            assertEquals(SyncWorkerStatus.Idle, worker.status.value)
        }

    @Test
    fun `gives a pending row with no queue entry one before reading the queue`() =
        rig {
            fx.reminder("m1")
            fx.container.syncQueue.remove(fx.entries().single().id)
            assertTrue(fx.entries().isEmpty())

            worker.drain()

            assertEquals("reminders:create", gateway.mutations.single().function)
        }

    // ---- push ---------------------------------------------------------------------------

    @Test
    fun `sends a reminder create and records the id the server returned`() =
        rig {
            fx.reminder("m1", schedule = dailyTimes(listOf("08:00"))) { it.copy(dosage = "10 mg", pillsRemaining = 30.0) }
            worker.drain()

            val call = gateway.mutations.single()
            assertEquals("reminders:create", call.function)
            assertEquals("m1", call.args["localId"])
            assertEquals(USER, call.args["userId"])
            assertEquals("10 mg", call.args["dosage"])
            assertEquals(30.0, call.args["pillsRemaining"])
            assertEquals(mapOf("type" to "daily", "times" to listOf("08:00")), call.args["schedule"])
            assertFalse(call.args.containsKey("rxcui"), "a field with no value is left out")
            assertNoIntegers(call.args)
            assertEquals("cid-m1", fx.container.reminders.getConvexId("m1"))
        }

    @Test
    fun `clears the entry and marks the source row synced after a push`() =
        rig {
            fx.reminder("m1")
            assertEquals("pending", fx.reminderRow("m1").syncStatus)

            worker.drain()

            assertTrue(fx.entries().isEmpty())
            assertEquals("synced", fx.reminderRow("m1").syncStatus)
        }

    @Test
    fun `an update addresses the server id and sends the changed fields`() =
        rig {
            fx.reminder("m1")
            worker.drain()
            gateway.calls.clear()

            fx.now += 1000
            fx.container.reminders.update("m1", UpdateReminderPatch(name = "Renamed", active = false))
            worker.drain()

            val call = gateway.mutations.single()
            assertEquals("reminders:update", call.function)
            assertEquals("cid-m1", call.args["id"])
            assertEquals("Renamed", call.args["name"])
            assertEquals(false, call.args["active"])
            assertNoIntegers(call.args)
        }

    @Test
    fun `a stop is a soft delete of the server id`() =
        rig {
            fx.reminder("m1")
            worker.drain()
            gateway.calls.clear()

            fx.container.reminders.softDelete("m1")
            worker.drain()

            assertEquals("reminders:softDelete", gateway.mutations.single().function)
            assertEquals(mapOf<String, Any?>("id" to "cid-m1"), gateway.mutations.single().args)
        }

    @Test
    fun `resolves the parent to a server id before creating an instance`() =
        rig {
            fx.reminder("m1")
            val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0 + 5_000))

            worker.drain()

            assertEquals(listOf("reminders:create", "reminderInstances:create"), gateway.mutations.map { it.function })
            val args = gateway.mutations.last().args
            assertEquals("cid-m1", args["reminderId"], "the parent's SERVER id, not its local one")
            assertEquals(instance.id, args["localId"])
            assertEquals((T0 + 5_000).toDouble(), args["scheduledTime"])
            assertEquals(0.0, args["snoozeCount"])
            assertNoIntegers(args)
            assertEquals("cid-${instance.id}", fx.container.instances.getConvexId(instance.id))
            assertEquals("synced", fx.instanceRow(instance.id).syncStatus)
        }

    @Test
    fun `asks the server for a parent the local row has not cached`() =
        rig {
            fx.reminder("m1")
            worker.drain()
            fx.forgetServerIds() // as if this device never learned the server's id for it
            gateway.calls.clear()
            fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            gateway.onQuery = { _, _ -> buildJsonObject { put("_id", "found-parent") } }

            worker.drain()

            val query = gateway.calls.single { it.kind == "query" }
            assertEquals("reminders:getByLocalId", query.function)
            assertEquals(mapOf<String, Any?>("localId" to "m1"), query.args)
            assertEquals("found-parent", gateway.mutations.single { it.function == "reminderInstances:create" }.args["reminderId"])
            assertEquals("found-parent", fx.container.reminders.getConvexId("m1"), "and remembers it")
        }

    @Test
    fun `a parent the server does not have fails the child and spends an attempt`() =
        rig {
            fx.reminder("m1")
            worker.drain()
            fx.forgetServerIds()
            fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))

            worker.drain()

            val entry = fx.entries().single()
            assertEquals(1, entry.attempts)
            assertEquals("Reminder m1 not found on Convex", entry.lastError)
            assertEquals(SyncWorkerStatus.Error, worker.status.value)
        }

    @Test
    fun `each answer goes to its own mutation`() =
        rig {
            fx.reminder("m1")
            val completed = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            val snoozed = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0 + 1))
            val skipped = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0 + 2))
            val missed = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0 + 3))
            worker.drain()
            gateway.calls.clear()

            fx.container.instances.markCompleted(completed.id, T0 + 10)
            fx.container.instances.markSnoozed(snoozed.id, T0 + 600_000)
            fx.container.instances.markSkipped(skipped.id, "nauseous")
            fx.container.instances.markMissed(missed.id)
            worker.drain()

            val byFunction = gateway.mutations.associateBy { it.function }
            assertEquals(mapOf("id" to "cid-${completed.id}", "completedAt" to (T0 + 10).toDouble()), byFunction["reminderInstances:markCompleted"]!!.args)
            assertEquals(mapOf("id" to "cid-${snoozed.id}", "snoozedUntil" to (T0 + 600_000).toDouble()), byFunction["reminderInstances:snooze"]!!.args)
            assertEquals(mapOf("id" to "cid-${skipped.id}", "notes" to "nauseous"), byFunction["reminderInstances:markSkipped"]!!.args)
            assertEquals(mapOf("id" to "cid-${missed.id}"), byFunction["reminderInstances:markMissed"]!!.args)
            assertTrue(fx.entries().isEmpty())
        }

    @Test
    fun `a take then an undo made offline reach the server as markCompleted then reopen, in that order`() =
        rig {
            fx.reminder("m1")
            val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            worker.drain()
            gateway.calls.clear()

            fx.container.instances.markCompleted(instance.id, T0 + 10)
            fx.container.instances.reopen(instance.id)
            worker.drain()

            assertEquals(listOf("reminderInstances:markCompleted", "reminderInstances:reopen"), gateway.mutations.map { it.function })
            assertEquals(mapOf("id" to "cid-${instance.id}"), gateway.mutations.last().args)
            assertTrue(fx.entries().isEmpty())
            assertEquals("synced", fx.instanceRow(instance.id).syncStatus)
        }

    @Test
    fun `an answer and its undo made before the first create drained go up in order behind it`() =
        rig {
            fx.reminder("m1")
            val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            fx.container.instances.markCompleted(instance.id, T0 + 10)
            fx.container.instances.reopen(instance.id)

            worker.drain()

            val instanceCalls = gateway.mutations.map { it.function }.filter { it.startsWith("reminderInstances:") }
            assertEquals(listOf("reminderInstances:create", "reminderInstances:markCompleted", "reminderInstances:reopen"), instanceCalls)
        }

    @Test
    fun `a snooze that carries no snoozedUntil is not sent`() =
        rig {
            fx.reminder("m1")
            val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            worker.drain()
            gateway.calls.clear()
            fx.container.syncQueue.enqueue(
                SyncEntityType.ReminderInstance,
                instance.id,
                SyncOperation.Update,
                buildJsonObject {
                    put("localId", instance.id)
                    put("userId", USER)
                    put("status", InstanceStatus.Snoozed.wire)
                    put("snoozedUntil", JsonNull)
                },
            )

            worker.drain()

            assertTrue(gateway.mutations.isEmpty())
            assertEquals("Snoozed instance is missing snoozedUntil", fx.entries().single().lastError)
        }

    @Test
    fun `an instance entry cannot be deleted`() =
        rig {
            fx.reminder("m1")
            val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            worker.drain()
            fx.container.syncQueue.enqueue(SyncEntityType.ReminderInstance, instance.id, SyncOperation.Delete, buildJsonObject { put("userId", USER) })
            worker.drain()
            assertEquals("Unknown operation delete", fx.entries().single().lastError)
        }

    @Test
    fun `a payload that is not an object fails the entry`() =
        rig {
            fx.container.database.syncQueueDao().insert(
                com.taskrx.mobile.data.db.SyncQueueEntity(
                    entityType = "reminder", entityId = "x", operation = "create", payload = "[1]", attempts = 0, lastError = null, createdAt = T0, deadLetter = false,
                ),
            )
            worker.drain()
            assertEquals("Payload is not an object", fx.entries().single().lastError)
        }

    @Test
    fun `a server answer that is not an id fails the entry`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> JsonPrimitive(3) }
            worker.drain()
            assertTrue(fx.entries().single().lastError!!.startsWith("Expected an id from the server"))
        }

    @Test
    fun `a looked-up parent that is not a document fails the child`() =
        rig {
            fx.reminder("m1")
            worker.drain()
            fx.forgetServerIds()
            fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            gateway.onQuery = { _, _ -> JsonObject(emptyMap()) }
            worker.drain()
            assertTrue(fx.entries().single().lastError!!.startsWith("Expected a document with an _id"))
        }

    @Test
    fun `an update for an instance only the server knows is looked up by local id`() =
        rig {
            fx.reminder("m1")
            val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))
            worker.drain()
            fx.forgetServerIds()
            gateway.calls.clear()
            fx.container.instances.markMissed(instance.id)
            gateway.onQuery = { _, _ -> buildJsonObject { put("_id", "inst-cid") } }

            worker.drain()

            assertEquals("reminderInstances:getByLocalId", gateway.calls.first { it.kind == "query" }.function)
            assertEquals(mapOf<String, Any?>("id" to "inst-cid"), gateway.mutations.single().args)

            // and an instance the server has never heard of is a failure, not a crash
            val other = fx.container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0 + 1))
            worker.drain()
            fx.forgetServerIds()
            fx.container.instances.markMissed(other.id)
            gateway.onQuery = { _, _ -> JsonNull }
            worker.drain()
            assertEquals("Reminder instance ${other.id} not found on Convex", fx.entries().single { it.entityId == other.id }.lastError)
        }

    // ---- failure, backoff, dead letter ----------------------------------------------------

    @Test
    fun `leaves an entry with attempts remaining in the queue and backs off`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> error("server down") }

            worker.drain()

            val entry = fx.entries().single()
            assertEquals(1, entry.attempts)
            assertEquals("server down", entry.lastError)
            assertFalse(entry.deadLetter)
            assertEquals("pending", fx.reminderRow("m1").syncStatus)
            assertEquals(listOf(1_000L), pauses)
            assertEquals(SyncWorkerStatus.Error, worker.status.value)
        }

    @Test
    fun `backoff grows with each failure of the same entry`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> error("down") }
            repeat(3) { worker.drain() }
            assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
        }

    @Test
    fun `dead-letters an entry on its fifth failure and marks the row error`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> error("rejected") }
            repeat(4) { worker.drain() }
            assertFalse(fx.container.syncQueue.pending().isEmpty())

            worker.drain()

            assertTrue(fx.entries().isEmpty(), "dead-lettered entries leave the pending list")
            val dead = fx.container.syncQueue.deadLettered().single()
            assertEquals(5, dead.attempts)
            assertEquals("rejected", dead.lastError)
            assertEquals("error", fx.reminderRow("m1").syncStatus)
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), pauses)
        }

    @Test
    fun `a server that has not re-authenticated yet spends no attempt, so the entry is never dead-lettered for it`() =
        rig {
            fx.reminder("m1")
            fx.reminder("m2")
            gateway.onMutation = { _, _ -> error("[CONVEX M(reminders:create)] Uncaught Error: Not authenticated") }
            repeat(8) { worker.drain() }

            assertEquals(listOf(0, 0), fx.entries().map { it.attempts })
            assertTrue(fx.container.syncQueue.deadLettered().isEmpty())
            assertEquals(SyncWorkerStatus.Error, worker.status.value)

            gateway.onMutation = { _, _ -> JsonPrimitive("srv") }
            worker.drain()
            assertTrue(fx.entries().isEmpty())
        }

    @Test
    fun `a push the server never answers times out, fails the attempt and frees the drain`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> awaitCancellation() }
            val impatient =
                PushWorker(fx.container.syncQueue, fx.container.reminders, fx.container.instances, gateway, connectivity, { user }, { pauses += it }, pushTimeoutMs = 50)
            impatient.drain()
            assertEquals(SyncWorkerStatus.Error, impatient.status.value)
            val entry = fx.entries().single()
            assertEquals(1, entry.attempts)
            assertTrue(entry.lastError!!.contains("Timed out"))
            // The guard is free: a second drain reaches the gateway again.
            impatient.drain()
            assertEquals(2, gateway.mutations.size)
        }

    @Test
    fun `an exception with no message is still recorded`() =
        rig {
            fx.reminder("m1")
            gateway.onMutation = { _, _ -> throw IllegalStateException() }
            worker.drain()
            assertTrue(fx.entries().single().lastError!!.contains("IllegalStateException"))
        }

    @Test
    fun `one failing entry does not stop the ones behind it`() =
        rig {
            fx.reminder("m1")
            fx.now += 1
            fx.reminder("m2")
            gateway.onMutation = { _, args -> if (args["localId"] == "m1") error("bad") else JsonPrimitive("cid-m2") }

            worker.drain()

            assertEquals(listOf("m1"), fx.entries().map { it.entityId })
            assertEquals("synced", fx.reminderRow("m2").syncStatus)
        }

    @Test
    fun `delivers a change made offline once connectivity returns`() =
        rig {
            connectivity.connected.value = false
            fx.reminder("m1")
            worker.drain()
            assertEquals(1, fx.entries().size)

            connectivity.connected.value = true
            worker.drain()

            assertTrue(fx.entries().isEmpty())
            assertEquals(SyncWorkerStatus.Idle, worker.status.value)
        }

    @Test
    fun `leaves another user's entries alone`() =
        rig {
            fx.container.reminders.create(CreateReminderInput(id = "local-1", userId = "local-user", kind = ReminderKind.Task, name = "Offline", schedule = dailyTimes(emptyList())))
            fx.reminder("mine")

            worker.drain()

            assertEquals(listOf("mine"), gateway.mutations.map { it.args["localId"] })
            val left = fx.entries().single()
            assertEquals("local-1", left.entityId)
            assertEquals(0, left.attempts, "not attempted, so not failed")
            assertEquals(0, worker.pendingCount(), "and not counted as this user's waiting work")
            assertNull(fx.container.syncQueue.deadLettered().firstOrNull())
        }

    @Test
    fun `counts only this user's waiting entries`() =
        rig {
            fx.reminder("m1")
            fx.container.database.syncQueueDao().insert(
                com.taskrx.mobile.data.db.SyncQueueEntity(
                    entityType = "reminder", entityId = "x", operation = "create", payload = "not json", attempts = 0, lastError = null, createdAt = T0, deadLetter = false,
                ),
            )
            assertEquals(2, worker.pendingCount(), "an unreadable payload is counted so its failure surfaces")
        }
}
