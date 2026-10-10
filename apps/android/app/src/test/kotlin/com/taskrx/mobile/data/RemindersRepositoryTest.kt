package com.taskrx.mobile.data

import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.WeeklyTimes
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.intervalTimes
import com.taskrx.core.domain.noneTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

@RunWith(RobolectricTestRunner::class)
class RemindersRepositoryTest {
    @Test
    fun `create stores the Reminder and returns it in domain form`() =
        withFixture(T0) {
            val created =
                container.reminders.create(
                    CreateReminderInput(
                        id = "m1",
                        userId = USER,
                        kind = ReminderKind.Medication,
                        name = "Atorvastatin",
                        schedule = dailyTimes(listOf("09:00", "21:00")),
                        dosage = "10 mg",
                        instructions = "with food",
                        priority = Priority.High,
                        tags = listOf("heart"),
                        pillsRemaining = 30.0,
                        pillsPerDose = 0.5,
                        refillReminderDays = 5,
                        caregiverIds = listOf("c1"),
                    ),
                )

            assertEquals(
                createReminder(
                    id = "m1",
                    userId = USER,
                    kind = ReminderKind.Medication,
                    name = "Atorvastatin",
                    createdAt = T0,
                    updatedAt = T0,
                    dosage = "10 mg",
                    instructions = "with food",
                    schedule = dailyTimes(listOf("09:00", "21:00")),
                    priority = Priority.High,
                    tags = listOf("heart"),
                    pillsRemaining = 30.0,
                    pillsPerDose = 0.5,
                    refillReminderDays = 5,
                    caregiverIds = listOf("c1"),
                ),
                created,
            )
            assertEquals(created, container.reminders.get("m1"))
        }

    @Test
    fun `create mints an id when none is supplied, and defaults the optional columns`() =
        withFixture(T0) {
            val created =
                container.reminders.create(
                    CreateReminderInput(userId = USER, kind = ReminderKind.Task, name = "Call", schedule = noneTimes()),
                )

            assertEquals("id-1", created.id)
            assertEquals(Priority.Normal, created.priority)
            assertEquals(emptyList(), created.tags)
            assertEquals(emptyList(), created.caregiverIds)
            assertTrue(created.active)
        }

    @Test
    fun `every schedule variant survives the round trip through the column`() =
        withFixture(T0) {
            val schedules =
                listOf(
                    dailyTimes(listOf("08:30")),
                    intervalTimes(6, T0),
                    weeklyTimes(listOf(1, 3), listOf("07:00")),
                    onceTimes(T0 + 1000),
                    noneTimes(),
                )
            schedules.forEachIndexed { i, schedule ->
                container.reminders.create(
                    CreateReminderInput(id = "r$i", userId = USER, kind = ReminderKind.Task, name = "n", schedule = schedule),
                )
            }

            schedules.forEachIndexed { i, schedule ->
                assertEquals(schedule, container.reminders.get("r$i")?.schedule)
            }
            // Spot-check the concrete types so a lossy codec cannot hide behind equality of `none`.
            assertTrue(container.reminders.get("r1")?.schedule is IntervalTimes)
            assertTrue(container.reminders.get("r2")?.schedule is WeeklyTimes)
            assertTrue(container.reminders.get("r3")?.schedule is OnceTimes)
        }

    @Test
    fun `the schedule column holds the JSON text core writes`() =
        withFixture(T0) {
            reminder("r1", schedule = dailyTimes(listOf("09:00")))

            val column = database.reminderDao().get("r1")!!.schedule

            assertEquals(com.taskrx.core.domain.serializeScheduleTimes(dailyTimes(listOf("09:00"))).toString(), column)
            assertTrue(column.contains("\"\$type\":\"DailyTimes\""))
        }

    @Test
    fun `an unreadable schedule or list column degrades instead of throwing`() =
        withFixture(T0) {
            reminder("r1", schedule = dailyTimes(listOf("09:00")))
            val dao = database.reminderDao()
            val row = dao.get("r1")!!

            dao.update(row.copy(schedule = "{not json", tags = "oops", caregiverIds = "[1, \"x\"]", kind = "weird", priority = "weird"))

            val read = container.reminders.get("r1")!!
            assertEquals(NoneTimes, read.schedule)
            assertEquals(emptyList(), read.tags)
            assertEquals(listOf("x"), read.caregiverIds)
            assertEquals(ReminderKind.Task, read.kind)
            assertEquals(Priority.Normal, read.priority)

            dao.update(row.copy(schedule = "{\"\$type\":\"Nope\"}"))
            assertEquals(NoneTimes, container.reminders.get("r1")!!.schedule)
        }

    @Test
    fun `create enqueues a create carrying only what the caller named`() =
        withFixture(T0) {
            container.reminders.create(
                CreateReminderInput(
                    id = "m1",
                    userId = USER,
                    kind = ReminderKind.Medication,
                    name = "Atorvastatin",
                    schedule = weeklyTimes(listOf(1), listOf("09:00")),
                    rxcui = "83367",
                    pillsRemaining = 30.0,
                ),
            )

            val entry = container.syncQueue.pending().single()
            assertEquals(SyncEntityType.Reminder, entry.entityType)
            assertEquals("m1", entry.entityId)
            assertEquals(SyncOperation.Create, entry.operation)
            assertEquals(0, entry.attempts)
            assertEquals(T0, entry.createdAt)

            val payload = payloads().single()
            assertEquals("m1", payload["localId"]!!.jsonPrimitive.content)
            assertEquals(USER, payload["userId"]!!.jsonPrimitive.content)
            assertEquals("medication", payload["kind"]!!.jsonPrimitive.content)
            assertEquals("Atorvastatin", payload["name"]!!.jsonPrimitive.content)
            assertEquals("83367", payload["rxcui"]!!.jsonPrimitive.content)
            assertEquals(30.0, payload["pillsRemaining"]!!.jsonPrimitive.content.toDouble())
            assertTrue(payload["active"]!!.jsonPrimitive.boolean)
            assertEquals(T0, payload["createdAt"]!!.jsonPrimitive.long)
            assertEquals(T0, payload["updatedAt"]!!.jsonPrimitive.long)
            // Unnamed => explicit null, never the local default: the worker tests `!== null`.
            for (key in listOf("dosage", "instructions", "priority", "tags", "pillsPerDose", "refillReminderDays", "caregiverIds")) {
                assertEquals(JsonNull, payload[key], key)
            }
        }

    @Test
    fun `the payload schedule is the shape the server validates, not the column's`() =
        withFixture(T0) {
            val shapes =
                mapOf(
                    dailyTimes(listOf("09:00")) to """{"type":"daily","times":["09:00"]}""",
                    intervalTimes(8, T0) to """{"type":"interval","hours":8,"startAt":$T0}""",
                    weeklyTimes(listOf(0, 6), listOf("10:15")) to """{"type":"weekly","daysOfWeek":[0,6],"times":["10:15"]}""",
                    onceTimes(T0) to """{"type":"once","at":$T0}""",
                    noneTimes() to """{"type":"none"}""",
                )
            shapes.keys.forEachIndexed { i, schedule ->
                container.reminders.create(
                    CreateReminderInput(id = "r$i", userId = USER, kind = ReminderKind.Task, name = "n", schedule = schedule),
                )
            }

            assertEquals(shapes.values.toList(), payloads().map { it["schedule"].toString() })
        }

    @Test
    fun `list is newest first, filtered by Kind, and hides stopped Reminders unless asked`() =
        withFixture(T0) {
            reminder("old", ReminderKind.Medication, createdAt = T0 - 3000)
            reminder("mid", ReminderKind.Task, createdAt = T0 - 2000)
            reminder("new", ReminderKind.Medication, createdAt = T0 - 1000)
            container.reminders.softDelete("new")
            database.reminderDao().insert(database.reminderDao().get("old")!!.copy(id = "other", userId = "someone-else"))

            assertEquals(listOf("mid", "old"), container.reminders.list(USER).map { it.id })
            assertEquals(listOf("new", "mid", "old"), container.reminders.list(USER, includeInactive = true).map { it.id })
            assertEquals(listOf("old"), container.reminders.list(USER, ReminderKind.Medication).map { it.id })
            assertEquals(listOf("new", "old"), container.reminders.list(USER, ReminderKind.Medication, true).map { it.id })
        }

    @Test
    fun `get is null for an unknown id`() =
        withFixture(T0) {
            assertNull(container.reminders.get("nope"))
        }

    @Test
    fun `update with an absent key leaves the column, and a present null clears it`() =
        withFixture(T0) {
            reminder("m1") {
                it.copy(dosage = "10 mg", instructions = "with food", rxcui = "83367", pillsRemaining = 30.0)
            }
            now = T0 + 5000

            val renamed = container.reminders.update("m1", UpdateReminderPatch(name = "Renamed"))
            assertEquals("Renamed", renamed.name)
            assertEquals("10 mg", renamed.dosage)
            assertEquals(30.0, renamed.pillsRemaining)
            assertEquals("83367", container.reminders.getRxcui("m1"))
            assertEquals(T0 + 5000, renamed.updatedAt)

            val cleared =
                container.reminders.update(
                    "m1",
                    UpdateReminderPatch(dosage = Change.To(null), rxcui = Change.To(null), pillsRemaining = Change.To(12.0)),
                )
            assertNull(cleared.dosage)
            assertNull(container.reminders.getRxcui("m1"))
            assertEquals(12.0, cleared.pillsRemaining)
            assertEquals("with food", cleared.instructions)
        }

    @Test
    fun `update can change every field`() =
        withFixture(T0) {
            reminder("m1")

            val updated =
                container.reminders.update(
                    "m1",
                    UpdateReminderPatch(
                        schedule = dailyTimes(listOf("07:00")),
                        priority = Priority.Critical,
                        tags = listOf("a"),
                        caregiverIds = listOf("c"),
                        instructions = Change.To("x"),
                        pillsPerDose = Change.To(2.0),
                        refillReminderDays = Change.To(3),
                        active = false,
                    ),
                )

            assertEquals(dailyTimes(listOf("07:00")), updated.schedule)
            assertEquals(Priority.Critical, updated.priority)
            assertEquals(listOf("a"), updated.tags)
            assertEquals(listOf("c"), updated.caregiverIds)
            assertEquals("x", updated.instructions)
            assertEquals(2.0, updated.pillsPerDose)
            assertEquals(3, updated.refillReminderDays)
            assertEquals(false, updated.active)
        }

    @Test
    fun `update enqueues the post-write state but only offers server-owned fields the patch set`() =
        withFixture(T0) {
            reminder("m1") { it.copy(priority = Priority.High, tags = listOf("t"), rxcui = "83367") }
            now = T0 + 10

            container.reminders.update("m1", UpdateReminderPatch(name = "Renamed"))

            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Update, entry.operation)
            val payload = payloads().last()
            assertEquals("Renamed", payload["name"]!!.jsonPrimitive.content)
            assertEquals("83367", payload["rxcui"]!!.jsonPrimitive.content)
            assertEquals(T0, payload["createdAt"]!!.jsonPrimitive.long)
            assertEquals(T0 + 10, payload["updatedAt"]!!.jsonPrimitive.long)
            // The column says High, but this edit never chose it: send null, leave the server's copy alone.
            assertEquals(JsonNull, payload["priority"])
            assertEquals(JsonNull, payload["tags"])
            assertEquals(JsonNull, payload["caregiverIds"])

            container.reminders.update("m1", UpdateReminderPatch(priority = Priority.Low, tags = listOf("u"), caregiverIds = listOf("c")))
            val chosen = payloads().last()
            assertEquals("low", chosen["priority"]!!.jsonPrimitive.content)
            assertEquals(JsonArray(listOf(JsonPrimitive("u"))), chosen["tags"]!!.jsonArray)
            assertEquals(JsonArray(listOf(JsonPrimitive("c"))), chosen["caregiverIds"]!!.jsonArray)
        }

    @Test
    fun `update returns the row to pending`() =
        withFixture(T0) {
            reminder("m1")
            container.reminders.markSynced("m1")
            assertEquals("synced", database.reminderDao().get("m1")!!.syncStatus)

            container.reminders.update("m1", UpdateReminderPatch(name = "x"))

            assertEquals("pending", database.reminderDao().get("m1")!!.syncStatus)
        }

    @Test
    fun `update of an unknown Reminder fails and enqueues nothing`() =
        withFixture(T0) {
            assertFailsWith<IllegalStateException> { container.reminders.update("nope", UpdateReminderPatch(name = "x")) }
            assertEquals(0, container.syncQueue.pending().size)
        }

    @Test
    fun `softDelete stops the Reminder, keeps its history, and enqueues a delete`() =
        withFixture(T0) {
            reminder("m1")
            container.instances.create(
                CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0, completedAt = null),
            )
            now = T0 + 99

            container.reminders.softDelete("m1")

            val row = container.reminders.get("m1")!!
            assertEquals(false, row.active)
            assertEquals(T0 + 99, row.updatedAt)
            // The cascade would have taken this with it had the row been deleted.
            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Delete, entry.operation)
            val payload = payloads().last()
            assertEquals(setOf("localId", "userId", "deletedAt"), payload.keys)
            assertEquals(T0 + 99, payload["deletedAt"]!!.jsonPrimitive.long)
        }

    @Test
    fun `softDelete of an absent row is a no-op`() =
        withFixture(T0) {
            container.reminders.softDelete("nope")

            assertEquals(0, container.syncQueue.pending().size)
        }

    @Test
    fun `a write whose enqueue fails is rolled back, not left pending without a queue entry`() =
        withFixture(T0) {
            database.openHelper.writableDatabase.execSQL("DROP TABLE sync_queue")

            assertFailsWith<Exception> {
                container.reminders.create(
                    CreateReminderInput(id = "m1", userId = USER, kind = ReminderKind.Task, name = "n", schedule = noneTimes()),
                )
            }

            assertNull(container.reminders.get("m1"))
        }

    @Test
    fun `enqueueExternalWrite chooses create for an unsynced row and update for a synced one`() =
        withFixture(T0) {
            reminder("m1") { it.copy(rxcui = "83367", priority = Priority.High, tags = listOf("t")) }
            val before = container.syncQueue.pending().size

            container.reminders.enqueueExternalWrite("m1")
            container.reminders.setConvexId("m1", "cvx-1")
            container.reminders.enqueueExternalWrite("m1")
            container.reminders.enqueueExternalWrite("absent")

            val added = container.syncQueue.pending().drop(before)
            assertEquals(listOf(SyncOperation.Create, SyncOperation.Update), added.map { it.operation })
            val payload = payloads().last()
            assertEquals("83367", payload["rxcui"]!!.jsonPrimitive.content)
            assertTrue(payload["active"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, payload["priority"])
            assertEquals(JsonNull, payload["tags"])
            assertEquals(JsonNull, payload["caregiverIds"])
            assertEquals("cvx-1", container.reminders.getConvexId("m1"))
        }

    @Test
    fun `reconcilePending enqueues only rows nothing in the queue would un-pend`() =
        withFixture(T0) {
            reminder("a")
            reminder("b")
            reminder("c")
            // a: live entry exists. b: its only entry is dead-lettered. c: entry removed (a lost enqueue).
            val entries = container.syncQueue.pending()
            val forB = entries.first { it.entityId == "b" }
            container.syncQueue.markDeadLettered(forB.id)
            container.syncQueue.remove(entries.first { it.entityId == "c" }.id)

            val enqueued = container.reminders.reconcilePending()

            assertEquals(2, enqueued)
            assertEquals(setOf("a", "b", "c"), container.syncQueue.pending().map { it.entityId }.toSet())
            // And a second pass is a no-op once the backlog is clear.
            assertEquals(0, container.reminders.reconcilePending())
        }

    @Test
    fun `reconcilePending leaves error and synced rows alone`() =
        withFixture(T0) {
            reminder("a")
            reminder("b")
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }
            container.reminders.markError("a", "boom")
            container.reminders.markSynced("b")

            assertEquals(0, container.reminders.reconcilePending())
            assertEquals("error", database.reminderDao().get("a")!!.syncStatus)
        }

    @Test
    fun `markError logs why the sync failed rather than staying silent`() =
        withFixture(T0) {
            reminder("a")
            container.reminders.markError("a", "boom")

            assertTrue(org.robolectric.shadows.ShadowLog.getLogs().any { it.msg.contains("sync error for a: boom") })
        }

    @Test
    fun `convex ids map both ways`() =
        withFixture(T0) {
            reminder("m1")
            assertNull(container.reminders.getConvexId("m1"))
            assertNull(container.reminders.localIdForConvexId("cvx-1"))

            container.reminders.setConvexId("m1", "cvx-1")

            assertEquals("cvx-1", container.reminders.getConvexId("m1"))
            assertEquals("m1", container.reminders.localIdForConvexId("cvx-1"))
            assertNull(container.reminders.getRxcui("absent"))
        }

    @Test
    fun `upsertMany writes pulled rows as synced without enqueueing, and updates in place`() =
        withFixture(T0) {
            val pulled =
                createReminder(
                    id = "m1",
                    userId = USER,
                    kind = ReminderKind.Medication,
                    name = "From server",
                    createdAt = 1,
                    updatedAt = 2,
                    schedule = onceTimes(T0),
                    priority = Priority.Low,
                    tags = listOf("s"),
                    caregiverIds = listOf("c"),
                    pillsRemaining = 4.0,
                    pillsPerDose = 1.0,
                    refillReminderDays = 2,
                    dosage = "d",
                    instructions = "i",
                )

            assertEquals(1, container.reminders.upsertMany(listOf(PulledReminder(pulled, "cvx-1", "999"))))

            assertEquals(pulled, container.reminders.get("m1"))
            assertEquals("999", container.reminders.getRxcui("m1"))
            assertEquals("cvx-1", container.reminders.getConvexId("m1"))
            assertEquals("synced", database.reminderDao().get("m1")!!.syncStatus)
            assertEquals(0, container.syncQueue.pending().size)

            container.reminders.upsertMany(listOf(PulledReminder(pulled.copy(name = "Edited"), "cvx-1", null)))
            assertEquals("Edited", container.reminders.get("m1")!!.name)
            assertEquals(1, container.reminders.list(USER).size)
        }

    @Test
    fun `upserting a parent does not delete the instances recorded against it`() =
        withFixture(T0) {
            reminder("m1")
            container.instances.create(CreateReminderInstanceInput(USER, "m1", ReminderKind.Medication, T0))

            container.reminders.upsertMany(
                listOf(PulledReminder(container.reminders.get("m1")!!.copy(name = "Pulled", updatedAt = T0 + 1), "cvx-1", null)),
            )

            assertEquals(1, container.instances.list(InstanceFilter(USER)).size)
        }

    private suspend fun com.taskrx.mobile.Fixture.payloads() = queuePayloads()
}
