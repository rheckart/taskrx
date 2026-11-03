package com.taskrx.mobile.data

import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.createReminder
import com.taskrx.core.domain.dailyTimes
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L
private const val DAY = 86_400_000L

private fun window(from: Long, until: Long? = null): JsonObject =
    JsonObject(buildMap { put("from", JsonPrimitive(from)); until?.let { put("until", JsonPrimitive(it)) } })

@RunWith(RobolectricTestRunner::class)
class PauseRepositoryTest {
    @Test
    fun `a new Reminder is not paused and a create says nothing about pauses`() =
        withFixture(T0) {
            val created = reminder("m1")

            assertEquals(emptyList(), created.pauses)
            assertEquals(JsonNull, queuePayloads().single()["pauses"])
        }

    @Test
    fun `pausing records the window, keeps the Reminder active and enqueues an update carrying it`() =
        withFixture(T0) {
            reminder("m1", schedule = dailyTimes(listOf("09:00")))
            now = T0 + 10

            val paused = container.reminders.pause("m1", from = T0 + DAY, until = T0 + 5 * DAY)

            assertEquals(listOf(PauseWindow(T0 + DAY, T0 + 5 * DAY)), paused.pauses)
            assertTrue(paused.active)
            assertEquals(paused, container.reminders.get("m1"))
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Update, entry.operation)
            assertEquals(JsonArray(listOf(window(T0 + DAY, T0 + 5 * DAY))), queuePayloads().last()["pauses"]!!.jsonArray)
            assertEquals("pending", database.reminderDao().get("m1")!!.syncStatus)
        }

    @Test
    fun `an open pause is sent without an end`() =
        withFixture(T0) {
            reminder("m1")

            container.reminders.pause("m1", from = T0, until = null)

            assertEquals(JsonArray(listOf(window(T0))), queuePayloads().last()["pauses"]!!.jsonArray)
        }

    @Test
    fun `resuming ends the running pause at this moment and keeps what it covered`() =
        withFixture(T0) {
            reminder("m1")
            container.reminders.pause("m1", from = T0, until = null)
            now = T0 + 3 * DAY

            val resumed = container.reminders.resume("m1")

            assertEquals(listOf(PauseWindow(T0, T0 + 3 * DAY)), resumed.pauses)
            assertEquals(JsonArray(listOf(window(T0, T0 + 3 * DAY))), queuePayloads().last()["pauses"]!!.jsonArray)
        }

    @Test
    fun `cancelling a pause that never started sends an empty list, which clears the server's copy`() =
        withFixture(T0) {
            reminder("m1")
            container.reminders.pause("m1", from = T0 + 5 * DAY, until = null)

            val cancelled = container.reminders.resume("m1")

            assertEquals(emptyList(), cancelled.pauses)
            assertEquals(JsonArray(emptyList()), queuePayloads().last()["pauses"]!!.jsonArray)
        }

    @Test
    fun `pausing again after a resume keeps the earlier window`() =
        withFixture(T0) {
            reminder("m1")
            container.reminders.pause("m1", from = T0, until = null)
            now = T0 + DAY
            container.reminders.resume("m1")
            now = T0 + 10 * DAY

            val again = container.reminders.pause("m1", from = now, until = null)

            assertEquals(listOf(PauseWindow(T0, T0 + DAY), PauseWindow(T0 + 10 * DAY)), again.pauses)
        }

    @Test
    fun `a second pause while one is running is refused and writes nothing`() =
        withFixture(T0) {
            reminder("m1")
            container.reminders.pause("m1", from = T0, until = null)
            val queued = container.syncQueue.pending().size

            assertFailsWith<IllegalArgumentException> { container.reminders.pause("m1", T0 + DAY, null) }

            assertEquals(queued, container.syncQueue.pending().size)
            assertEquals(1, container.reminders.get("m1")!!.pauses.size)
        }

    @Test
    fun `resuming a Reminder that is not paused changes nothing about its windows`() =
        withFixture(T0) {
            reminder("m1")

            assertEquals(emptyList(), container.reminders.resume("m1").pauses)
        }

    @Test
    fun `pausing or resuming an unknown Reminder fails`() =
        withFixture(T0) {
            assertFailsWith<IllegalStateException> { container.reminders.pause("nope", T0, null) }
            assertFailsWith<IllegalStateException> { container.reminders.resume("nope") }
        }

    @Test
    fun `an edit that did not touch pauses neither loses them nor sends them`() =
        withFixture(T0) {
            reminder("m1")
            container.reminders.pause("m1", from = T0, until = T0 + DAY)

            container.reminders.update("m1", UpdateReminderPatch(name = "Renamed"))

            assertEquals(listOf(PauseWindow(T0, T0 + DAY)), container.reminders.get("m1")!!.pauses)
            assertEquals(JsonNull, queuePayloads().last()["pauses"])
        }

    @Test
    fun `an unreadable pauses column reads as not paused`() =
        withFixture(T0) {
            reminder("m1")
            val dao = database.reminderDao()

            dao.update(dao.get("m1")!!.copy(pauses = "{not json"))
            assertEquals(emptyList(), container.reminders.get("m1")!!.pauses)

            dao.update(dao.get("m1")!!.copy(pauses = """[{"from":"x"}]"""))
            assertEquals(emptyList(), container.reminders.get("m1")!!.pauses)
        }

    @Test
    fun `a pulled Reminder keeps its windows`() =
        withFixture(T0) {
            val pulled =
                createReminder(
                    "m1", USER, ReminderKind.Medication, "From server", 1, 2,
                    pauses = listOf(PauseWindow(10, 20), PauseWindow(30)),
                )

            container.reminders.upsertMany(listOf(PulledReminder(pulled, "cvx-1", null)))

            assertEquals(pulled.pauses, container.reminders.get("m1")!!.pauses)
        }

    @Test
    fun `re-enqueueing a row sends its windows only when it has some`() =
        withFixture(T0) {
            reminder("plain")
            reminder("held")
            container.reminders.pause("held", T0, null)
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }

            container.reminders.enqueueExternalWrite("plain")
            container.reminders.enqueueExternalWrite("held")

            val byId = container.syncQueue.pending().associate { it.entityId to it }
            val plain = kotlinx.serialization.json.Json.parseToJsonElement(byId.getValue("plain").payload) as JsonObject
            val held = kotlinx.serialization.json.Json.parseToJsonElement(byId.getValue("held").payload) as JsonObject
            assertEquals(JsonNull, plain["pauses"])
            assertEquals(JsonArray(listOf(window(T0))), held["pauses"]!!.jsonArray)
        }
}
