package com.taskrx.mobile.data

import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

@RunWith(RobolectricTestRunner::class)
class SyncQueueRepositoryTest {
    private suspend fun com.taskrx.mobile.Fixture.push(id: String) =
        container.syncQueue.enqueue(SyncEntityType.Reminder, id, SyncOperation.Create, buildJsonObject { put("localId", id) })

    @Test
    fun `entries drain oldest first and carry the payload verbatim`() =
        withFixture(T0) {
            now = T0 + 2
            push("later")
            now = T0 + 1
            push("earlier")

            val pending = container.syncQueue.pending()

            assertEquals(listOf("earlier", "later"), pending.map { it.entityId })
            val entry = pending.first()
            assertEquals(SyncEntityType.Reminder, entry.entityType)
            assertEquals(SyncOperation.Create, entry.operation)
            assertEquals("""{"localId":"earlier"}""", entry.payload)
            assertEquals(0, entry.attempts)
            assertNull(entry.lastError)
            assertFalse(entry.deadLetter)
            assertEquals(T0 + 1, entry.createdAt)
        }

    @Test
    fun `same-millisecond entries keep the order they were enqueued in`() =
        withFixture(T0) {
            push("a")
            push("b")
            push("c")

            assertEquals(listOf("a", "b", "c"), container.syncQueue.pending().map { it.entityId })
        }

    @Test
    fun `a failure counts an attempt and keeps the entry in the queue`() =
        withFixture(T0) {
            push("a")
            val id = container.syncQueue.pending().single().id

            container.syncQueue.markFailed(id, "boom")
            container.syncQueue.markFailed(id, "bang")

            val entry = container.syncQueue.pending().single()
            assertEquals(2, entry.attempts)
            assertEquals("bang", entry.lastError)
            assertFalse(entry.deadLetter)
        }

    @Test
    fun `a dead-lettered entry leaves pending but is retained, and requeue re-arms it`() =
        withFixture(T0) {
            push("a")
            push("b")
            val a = container.syncQueue.pending().first().id
            container.syncQueue.markFailed(a, "boom")

            container.syncQueue.markDeadLettered(a)

            assertEquals(listOf("b"), container.syncQueue.pending().map { it.entityId })
            val dead = container.syncQueue.deadLettered().single()
            assertEquals("a", dead.entityId)
            assertTrue(dead.deadLetter)
            assertEquals(1, dead.attempts)

            container.syncQueue.requeue(a)

            assertTrue(container.syncQueue.deadLettered().isEmpty())
            val back = container.syncQueue.pending().first { it.id == a }
            assertEquals(0, back.attempts)
            assertNull(back.lastError)
        }

    @Test
    fun `remove deletes only the named entry`() =
        withFixture(T0) {
            push("a")
            push("b")

            container.syncQueue.remove(container.syncQueue.pending().first().id)

            assertEquals(listOf("b"), container.syncQueue.pending().map { it.entityId })
        }
}
