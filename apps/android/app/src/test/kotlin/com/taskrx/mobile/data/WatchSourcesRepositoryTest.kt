package com.taskrx.mobile.data

import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.WatchedSource
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

private fun source(
    kind: SourceKind = SourceKind.Email,
    value: String = "waste@city.example",
    label: String = "Waste",
) = CreateWatchedSourceInput(userId = USER, kind = kind, value = value, label = label)

/**
 * Watched sources through the repository: the gate on what is stored, never on what is
 * proposed. A source alone moves nothing, so these tests only check the row and the wire.
 */
@RunWith(RobolectricTestRunner::class)
class WatchSourcesRepositoryTest {
    @Test
    fun `a new source is stored and enqueued as a create`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())

            assertEquals(SourceKind.Email, created.kind)
            assertEquals("waste@city.example", created.value)
            assertEquals(created, container.watchSources.get(created.id))
            val entry = container.syncQueue.pending().single()
            assertEquals(SyncEntityType.WatchSource, entry.entityType)
            assertEquals(SyncOperation.Create, entry.operation)
            val payload = queuePayloads().single()
            assertEquals(created.id, payload["localId"]!!.jsonPrimitive.content)
            assertEquals("email", payload["kind"]!!.jsonPrimitive.content)
            assertEquals("waste@city.example", payload["value"]!!.jsonPrimitive.content)
            assertEquals("Waste", payload["label"]!!.jsonPrimitive.content)
        }

    @Test
    fun `listing is oldest first`() =
        withFixture(T0) {
            container.watchSources.create(source(value = "a@b", label = "A"))
            now = T0 + 10
            container.watchSources.create(source(value = "c@d", label = "C"))

            assertEquals(listOf("A", "C"), container.watchSources.list(USER).map { it.label })
        }

    @Test
    fun `renaming keeps the address and enqueues an update`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())
            now = T0 + 100

            val renamed = container.watchSources.updateLabel(created.id, "City waste")

            assertEquals("City waste", renamed.label)
            assertEquals(created.value, renamed.value)
            assertEquals(T0 + 100, renamed.updatedAt)
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Update, entry.operation)
            assertEquals("City waste", queuePayloads().last()["label"]!!.jsonPrimitive.content)
        }

    @Test
    fun `renaming an unknown source fails`() =
        withFixture(T0) {
            kotlin.test.assertFailsWith<IllegalStateException> {
                container.watchSources.updateLabel("nope", "x")
            }
        }

    @Test
    fun `removing a synced source deletes the row and enqueues a delete carrying the server id`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())
            container.watchSources.setConvexId(created.id, "cvx-1")

            container.watchSources.remove(created.id)

            assertNull(container.watchSources.get(created.id))
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Delete, entry.operation)
            assertEquals(USER, queuePayloads().last()["userId"]!!.jsonPrimitive.content)
            assertEquals("cvx-1", queuePayloads().last()["convexId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `removing a source the server never saw drops its create instead of queueing a delete`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())

            container.watchSources.remove(created.id)

            assertNull(container.watchSources.get(created.id))
            assertTrue(container.syncQueue.pending().isEmpty())
        }

    @Test
    fun `removing a source that is already gone writes nothing`() =
        withFixture(T0) {
            val queued = container.syncQueue.pending().size

            container.watchSources.remove("nope")

            assertEquals(queued, container.syncQueue.pending().size)
        }

    @Test
    fun `re-enqueueing chooses create before the server has seen the row and update after`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }

            container.watchSources.enqueueExternalWrite(created.id)
            assertEquals(SyncOperation.Create, container.syncQueue.pending().single().operation)

            container.watchSources.setConvexId(created.id, "cvx-1")
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }
            container.watchSources.enqueueExternalWrite(created.id)
            assertEquals(SyncOperation.Update, container.syncQueue.pending().single().operation)
        }

    @Test
    fun `reconciliation enqueues a pending row that lost its queue entry`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }

            assertEquals(1, container.watchSources.reconcilePending())
            assertEquals(created.id, container.syncQueue.pending().single().entityId)
            assertEquals(0, container.watchSources.reconcilePending())
        }

    @Test
    fun `a pulled source is written synced with no queue entry`() =
        withFixture(T0) {
            val pulled =
                WatchedSource(
                    id = "w1",
                    userId = USER,
                    kind = SourceKind.App,
                    value = "com.example.app",
                    label = "From server",
                    createdAt = T0,
                    updatedAt = T0 + 5,
                )

            assertEquals(1, container.watchSources.upsertMany(listOf(PulledWatchedSource(pulled, "cvx-1"))))

            assertEquals(pulled, container.watchSources.get("w1"))
            assertEquals("cvx-1", container.watchSources.getConvexId("w1"))
            assertEquals("w1", container.watchSources.localIdForConvexId("cvx-1"))
            assertTrue(container.syncQueue.pending().isEmpty())
            assertEquals("synced", database.watchedSourceDao().get("w1")!!.syncStatus)
        }

    @Test
    fun `an unknown kind reads as an email`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())
            val dao = database.watchedSourceDao()

            dao.update(dao.get(created.id)!!.copy(kind = "sms"))

            assertEquals(SourceKind.Email, container.watchSources.get(created.id)!!.kind)
        }

    @Test
    fun `marking a row synced, pending or errored is visible on the row`() =
        withFixture(T0) {
            val created = container.watchSources.create(source())
            val dao = database.watchedSourceDao()

            container.watchSources.markSynced(created.id)
            assertEquals("synced", dao.get(created.id)!!.syncStatus)

            container.watchSources.markPending(created.id)
            assertEquals("pending", dao.get(created.id)!!.syncStatus)

            container.watchSources.markError(created.id, "boom")
            assertEquals("error", dao.get(created.id)!!.syncStatus)
        }
}
