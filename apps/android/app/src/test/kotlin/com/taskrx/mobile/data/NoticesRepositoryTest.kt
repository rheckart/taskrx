package com.taskrx.mobile.data

import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val T0 = 1_780_000_000_000L

private fun notice(
    sourceKind: SourceKind = SourceKind.Email,
    sourceValue: String = "waste@city.example",
    title: String = "Bins moved",
    sentence: String? = "Collection moves to Thursday.",
    receivedAt: Long = T0,
    externalId: String? = null,
    resolution: NoticeResolution = NoticeResolution.None,
    resolvedAt: Long? = null,
) = CreateNoticeInput(
    userId = USER,
    sourceKind = sourceKind,
    sourceValue = sourceValue,
    title = title,
    sentence = sentence,
    receivedAt = receivedAt,
    externalId = externalId,
    resolution = resolution,
    resolvedAt = resolvedAt,
)

/**
 * Notices through the repository: the row, the sync payload and what a pull does with them.
 *
 * Nothing here parses a date or moves a task; a Notice is evidence, and the tests check that
 * it survives the trip to the store and the wire with the same fields.
 */
@RunWith(RobolectricTestRunner::class)
class NoticesRepositoryTest {
    @Test
    fun `a new notice is stored and enqueued as a create`() =
        withFixture(T0) {
            val created = container.notices.create(notice(externalId = "email-1"))

            assertEquals(SourceKind.Email, created.sourceKind)
            assertEquals(NoticeResolution.None, created.resolution)
            assertEquals(created, container.notices.get(created.id))
            val entry = container.syncQueue.pending().single()
            assertEquals(SyncEntityType.Notice, entry.entityType)
            assertEquals(SyncOperation.Create, entry.operation)
            val payload = queuePayloads().single()
            assertEquals(created.id, payload["localId"]!!.jsonPrimitive.content)
            assertEquals("email", payload["sourceKind"]!!.jsonPrimitive.content)
            assertEquals("waste@city.example", payload["sourceValue"]!!.jsonPrimitive.content)
            assertEquals("Bins moved", payload["title"]!!.jsonPrimitive.content)
            assertEquals("Collection moves to Thursday.", payload["sentence"]!!.jsonPrimitive.content)
            assertEquals("email-1", payload["externalId"]!!.jsonPrimitive.content)
            assertEquals("none", payload["resolution"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, payload["resolvedAt"])
        }

    @Test
    fun `a notice with no sentence or external id sends nulls`() =
        withFixture(T0) {
            container.notices.create(notice(sentence = null, externalId = null))

            val payload = queuePayloads().single()
            assertEquals(JsonNull, payload["sentence"])
            assertEquals(JsonNull, payload["externalId"])
        }

    @Test
    fun `listing is newest received first and can be narrowed to one resolution`() =
        withFixture(T0) {
            container.notices.create(notice(title = "old", receivedAt = T0))
            container.notices.create(notice(title = "new", receivedAt = T0 + 10))
            container.notices.create(notice(title = "done", receivedAt = T0 + 5, resolution = NoticeResolution.Dismissed))

            assertEquals(listOf("new", "done", "old"), container.notices.list(USER).map { it.title })
            assertEquals(listOf("done"), container.notices.list(USER, NoticeResolution.Dismissed).map { it.title })
            assertEquals(2, container.notices.list(USER, NoticeResolution.None).size)
        }

    @Test
    fun `the unresolved count is what the More menu shows`() =
        withFixture(T0) {
            container.notices.create(notice(title = "a"))
            container.notices.create(notice(title = "b"))
            container.notices.create(notice(title = "c", resolution = NoticeResolution.Applied))

            assertEquals(2, container.notices.unresolvedCount(USER))
        }

    @Test
    fun `resolving keeps the source and sentence and enqueues an update`() =
        withFixture(T0) {
            val created = container.notices.create(notice())
            now = T0 + 100

            val resolved = container.notices.resolve(created.id, NoticeResolution.Applied)

            assertEquals(NoticeResolution.Applied, resolved.resolution)
            assertEquals(T0 + 100, resolved.resolvedAt)
            assertEquals(created.title, resolved.title)
            assertEquals(created.sentence, resolved.sentence)
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Update, entry.operation)
            val payload = queuePayloads().last()
            assertEquals("applied", payload["resolution"]!!.jsonPrimitive.content)
            assertEquals(T0 + 100, payload["resolvedAt"]!!.jsonPrimitive.content.toLong())
        }

    @Test
    fun `resolving an unknown notice fails`() =
        withFixture(T0) {
            kotlin.test.assertFailsWith<IllegalStateException> {
                container.notices.resolve("nope", NoticeResolution.Dismissed)
            }
        }

    @Test
    fun `removing a synced notice deletes the row and enqueues a delete carrying the server id`() =
        withFixture(T0) {
            val created = container.notices.create(notice())
            container.notices.setConvexId(created.id, "cvx-1")

            container.notices.remove(created.id)

            assertNull(container.notices.get(created.id))
            val entry = container.syncQueue.pending().last()
            assertEquals(SyncOperation.Delete, entry.operation)
            assertEquals(USER, queuePayloads().last()["userId"]!!.jsonPrimitive.content)
            assertEquals("cvx-1", queuePayloads().last()["convexId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `removing a notice the server never saw drops its create instead of queueing a delete`() =
        withFixture(T0) {
            val created = container.notices.create(notice())

            container.notices.remove(created.id)

            assertNull(container.notices.get(created.id))
            assertTrue(container.syncQueue.pending().isEmpty())
        }

    @Test
    fun `removing a notice that is already gone writes nothing`() =
        withFixture(T0) {
            val queued = container.syncQueue.pending().size

            container.notices.remove("nope")

            assertEquals(queued, container.syncQueue.pending().size)
        }

    @Test
    fun `an external id finds the notice already stored for it`() =
        withFixture(T0) {
            val created = container.notices.create(notice(externalId = "email-1"))

            assertEquals(created, container.notices.findByExternalId(USER, "email-1"))
            assertNull(container.notices.findByExternalId(USER, "email-2"))
        }

    @Test
    fun `re-enqueueing chooses create before the server has seen the row and update after`() =
        withFixture(T0) {
            val created = container.notices.create(notice())
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }

            container.notices.enqueueExternalWrite(created.id)
            assertEquals(SyncOperation.Create, container.syncQueue.pending().single().operation)

            container.notices.setConvexId(created.id, "cvx-1")
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }
            container.notices.enqueueExternalWrite(created.id)
            assertEquals(SyncOperation.Update, container.syncQueue.pending().single().operation)
        }

    @Test
    fun `reconciliation enqueues a pending row that lost its queue entry`() =
        withFixture(T0) {
            val created = container.notices.create(notice())
            container.syncQueue.pending().forEach { container.syncQueue.remove(it.id) }

            assertEquals(1, container.notices.reconcilePending())
            assertEquals(created.id, container.syncQueue.pending().single().entityId)
            assertEquals(0, container.notices.reconcilePending())
        }

    @Test
    fun `a pulled notice is written synced with no queue entry`() =
        withFixture(T0) {
            val pulled =
                Notice(
                    id = "n1",
                    userId = USER,
                    sourceKind = SourceKind.App,
                    sourceValue = "com.example.app",
                    title = "From server",
                    sentence = "Tomorrow at 10.",
                    receivedAt = T0,
                    externalId = "app-1",
                    resolution = NoticeResolution.Dismissed,
                    resolvedAt = T0 + 5,
                    createdAt = T0,
                    updatedAt = T0 + 5,
                )

            assertEquals(1, container.notices.upsertMany(listOf(PulledNotice(pulled, "cvx-1"))))

            assertEquals(pulled, container.notices.get("n1"))
            assertEquals("cvx-1", container.notices.getConvexId("n1"))
            assertEquals("n1", container.notices.localIdForConvexId("cvx-1"))
            assertTrue(container.syncQueue.pending().isEmpty())
            assertEquals("synced", database.noticeDao().get("n1")!!.syncStatus)
        }

    @Test
    fun `an unknown source kind or resolution reads as the safe default`() =
        withFixture(T0) {
            val created = container.notices.create(notice())
            val dao = database.noticeDao()

            dao.update(dao.get(created.id)!!.copy(sourceKind = "sms", resolution = "maybe"))

            val read = container.notices.get(created.id)!!
            assertEquals(SourceKind.Share, read.sourceKind)
            assertEquals(NoticeResolution.None, read.resolution)
        }

    @Test
    fun `marking a row synced, pending or errored is visible on the row`() =
        withFixture(T0) {
            val created = container.notices.create(notice())
            val dao = database.noticeDao()

            container.notices.markSynced(created.id)
            assertEquals("synced", dao.get(created.id)!!.syncStatus)

            container.notices.markPending(created.id)
            assertEquals("pending", dao.get(created.id)!!.syncStatus)

            container.notices.markError(created.id, "boom")
            assertEquals("error", dao.get(created.id)!!.syncStatus)
        }

    @Test
    fun `a payload is a JSON object with every field the server takes`() =
        withFixture(T0) {
            container.notices.create(notice())

            val payload: JsonObject = queuePayloads().single()
            assertEquals(
                setOf(
                    "localId", "userId", "sourceKind", "sourceValue", "title", "sentence",
                    "receivedAt", "externalId", "resolution", "resolvedAt", "createdAt", "updatedAt",
                ),
                payload.keys,
            )
        }
}
