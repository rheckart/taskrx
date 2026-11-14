package com.taskrx.mobile.data

import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.withOverride
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L
private const val DAY = 86_400_000L

private fun move(from: Long, to: Long, noticeId: String? = null): JsonObject =
    JsonObject(
        buildMap {
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
            noticeId?.let { put("noticeId", JsonPrimitive(it)) }
        },
    )

/**
 * Occurrence overrides through the repository: the column, the sync payload and what a pull
 * does with them.
 *
 * The rule about *which* moments may move is `:core`'s (`moveRefusal`); what is checked here is
 * that a move survives the trip to the store and the wire with the same three fields.
 */
@RunWith(RobolectricTestRunner::class)
class OccurrenceOverrideRepositoryTest {
    @Test
    fun `a new Reminder is unmoved and a create says nothing about moves`() =
        withFixture(T0) {
            val created = reminder("t1", kind = ReminderKind.Task)

            assertEquals(emptyList(), created.overrides)
            assertEquals(JsonNull, queuePayloads().single()["overrides"])
        }

    @Test
    fun `a move is stored, and enqueued as an update carrying it`() =
        withFixture(T0) {
            reminder("t1", kind = ReminderKind.Task, schedule = dailyTimes(listOf("09:00")))
            val task = container.reminders.get("t1")!!
            val from = T0 + DAY
            val to = T0 + 2 * DAY

            val moved =
                container.reminders.update(
                    "t1",
                    UpdateReminderPatch(overrides = task.overrides.withOverride(OccurrenceOverride(from, to))),
                )

            assertEquals(listOf(OccurrenceOverride(from, to)), moved.overrides)
            assertEquals(moved, container.reminders.get("t1"))
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Update, entry.operation)
            assertEquals(JsonArray(listOf(move(from, to))), queuePayloads().last()["overrides"]!!.jsonArray)
        }

    @Test
    fun `a move that a notice caused carries the notice id`() =
        withFixture(T0) {
            reminder("t1", kind = ReminderKind.Task)

            container.reminders.update(
                "t1",
                UpdateReminderPatch(overrides = listOf(OccurrenceOverride(T0 + DAY, T0 + 2 * DAY, noticeId = "notice-1"))),
            )

            assertEquals(
                JsonArray(listOf(move(T0 + DAY, T0 + 2 * DAY, noticeId = "notice-1"))),
                queuePayloads().last()["overrides"]!!.jsonArray,
            )
        }

    @Test
    fun `removing the last move sends an empty list, which clears the server's copy`() =
        withFixture(T0) {
            reminder("t1", kind = ReminderKind.Task)
            container.reminders.update("t1", UpdateReminderPatch(overrides = listOf(OccurrenceOverride(T0 + DAY, T0 + 2 * DAY))))

            container.reminders.update("t1", UpdateReminderPatch(overrides = emptyList()))

            assertEquals(emptyList(), container.reminders.get("t1")!!.overrides)
            assertEquals(JsonArray(emptyList()), queuePayloads().last()["overrides"]!!.jsonArray)
        }

    @Test
    fun `an edit that did not touch moves neither loses them nor sends them`() =
        withFixture(T0) {
            reminder("t1", kind = ReminderKind.Task)
            container.reminders.update("t1", UpdateReminderPatch(overrides = listOf(OccurrenceOverride(T0 + DAY, T0 + 2 * DAY))))

            container.reminders.update("t1", UpdateReminderPatch(name = "Renamed"))

            assertEquals(listOf(OccurrenceOverride(T0 + DAY, T0 + 2 * DAY)), container.reminders.get("t1")!!.overrides)
            assertEquals(JsonNull, queuePayloads().last()["overrides"])
        }

    @Test
    fun `an unreadable overrides column reads as unmoved`() =
        withFixture(T0) {
            reminder("t1", kind = ReminderKind.Task)
            val dao = database.reminderDao()

            dao.update(dao.get("t1")!!.copy(overrides = "{not json"))
            assertEquals(emptyList(), container.reminders.get("t1")!!.overrides)

            dao.update(dao.get("t1")!!.copy(overrides = """[{"from":1}]"""))
            assertEquals(emptyList(), container.reminders.get("t1")!!.overrides)
        }

    @Test
    fun `a pulled Reminder keeps its moves`() =
        withFixture(T0) {
            val pulled =
                createReminder(
                    "t1", USER, ReminderKind.Task, "From server", 1, 2,
                    overrides = listOf(OccurrenceOverride(10, 20), OccurrenceOverride(30, 40, noticeId = "n1")),
                )

            container.reminders.upsertMany(listOf(PulledReminder(pulled, "cvx-1", null)))

            assertEquals(pulled.overrides, container.reminders.get("t1")!!.overrides)
        }

    @Test
    fun `re-enqueueing a row sends its moves only when it has some`() =
        withFixture(T0) {
            reminder("plain", kind = ReminderKind.Task)
            reminder("moved", kind = ReminderKind.Task)
            container.reminders.update("moved", UpdateReminderPatch(overrides = listOf(OccurrenceOverride(T0 + DAY, T0 + 2 * DAY))))
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }

            container.reminders.enqueueExternalWrite("plain")
            container.reminders.enqueueExternalWrite("moved")

            val byId = container.syncQueue.pending().associate { it.entityId to it }
            val plain = Json.parseToJsonElement(byId.getValue("plain").payload) as JsonObject
            val moved = Json.parseToJsonElement(byId.getValue("moved").payload) as JsonObject
            assertEquals(JsonNull, plain["overrides"])
            assertEquals(JsonArray(listOf(move(T0 + DAY, T0 + 2 * DAY))), moved["overrides"]!!.jsonArray)
        }
}
