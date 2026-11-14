package com.taskrx.mobile.data

import androidx.room.withTransaction
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.SourceKind
import com.taskrx.mobile.data.db.NoticeEntity
import com.taskrx.mobile.data.db.SyncStatus
import com.taskrx.mobile.data.db.TaskRxDatabase

data class CreateNoticeInput(
    val userId: String,
    val sourceKind: SourceKind,
    val sourceValue: String,
    val title: String,
    val sentence: String? = null,
    val receivedAt: Long,
    val externalId: String? = null,
    val resolution: NoticeResolution = NoticeResolution.None,
    val resolvedAt: Long? = null,
)

/** A notice as it arrives from a remote pull. */
data class PulledNotice(
    val notice: Notice,
    val convexId: String,
)

/**
 * Local repository for Notices: outside messages a watched source admitted.
 *
 * A Notice is evidence, not a commitment. Nothing here parses a date or moves a task; that
 * is the extractor and proposal rules in `:core`, applied by the screens once the person
 * agrees. Writes enqueue their `sync_queue` row in the same transaction as the row write,
 * exactly as [RemindersRepository].
 *
 * [create] is intentionally not de-duplicated on `externalId`. The road that supplies one (a
 * JMAP email id) is expected to check [findByExternalId] first, because only that road knows
 * what "the same message again" means.
 */
class NoticesRepository(
    private val database: TaskRxDatabase,
    private val queue: SyncQueueRepository,
    private val clock: Clock = SystemClock,
    private val newId: IdGenerator = RandomUuid,
) {
    private val dao = database.noticeDao()

    /** Stores one notice and enqueues a `create`. */
    suspend fun create(input: CreateNoticeInput): Notice =
        database.withTransaction {
            val now = clock()
            val id = newId()
            val row =
                NoticeEntity(
                    id = id,
                    userId = input.userId,
                    convexId = null,
                    sourceKind = input.sourceKind.wire,
                    sourceValue = input.sourceValue,
                    title = input.title,
                    sentence = input.sentence,
                    receivedAt = input.receivedAt,
                    externalId = input.externalId,
                    resolution = input.resolution.wire,
                    resolvedAt = input.resolvedAt,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                )
            dao.insert(row)
            queue.enqueue(
                SyncEntityType.Notice,
                id,
                SyncOperation.Create,
                SyncPayloads.notice(
                    localId = id,
                    userId = row.userId,
                    sourceKind = input.sourceKind,
                    sourceValue = row.sourceValue,
                    title = row.title,
                    sentence = row.sentence,
                    receivedAt = row.receivedAt,
                    externalId = row.externalId,
                    resolution = input.resolution,
                    resolvedAt = row.resolvedAt,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                ),
            )
            Codecs.toNotice(row)
        }

    /** [userId]'s notices, newest received first. [resolution] null means every notice. */
    suspend fun list(userId: String, resolution: NoticeResolution? = null): List<Notice> =
        dao.list(userId, resolution?.wire).map(Codecs::toNotice)

    suspend fun get(id: String): Notice? = dao.get(id)?.let(Codecs::toNotice)

    /** How many notices are still waiting for a decision: the More menu's badge. */
    suspend fun unresolvedCount(userId: String): Int = dao.unresolvedCount(userId)

    /** The notice already stored for this external id, when the road supplies one. */
    suspend fun findByExternalId(userId: String, externalId: String): Notice? =
        dao.findByExternalId(userId, externalId)?.let(Codecs::toNotice)

    /**
     * Settles a notice: applied to a task, or dismissed. The source and sentence never change.
     * Returns to `pending` and enqueues an `update`.
     */
    suspend fun resolve(id: String, resolution: NoticeResolution, resolvedAt: Long? = null): Notice =
        database.withTransaction {
            val row = checkNotNull(dao.get(id)) { "Notice $id not found" }
            val now = clock()
            val next =
                row.copy(
                    resolution = resolution.wire,
                    resolvedAt = resolvedAt ?: now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                )
            dao.update(next)
            queue.enqueue(
                SyncEntityType.Notice,
                id,
                SyncOperation.Update,
                SyncPayloads.notice(
                    localId = next.id,
                    userId = next.userId,
                    sourceKind = SourceKind.fromWire(next.sourceKind) ?: SourceKind.Share,
                    sourceValue = next.sourceValue,
                    title = next.title,
                    sentence = next.sentence,
                    receivedAt = next.receivedAt,
                    externalId = next.externalId,
                    resolution = resolution,
                    resolvedAt = next.resolvedAt,
                    createdAt = next.createdAt,
                    updatedAt = next.updatedAt,
                ),
            )
            Codecs.toNotice(next)
        }

    /** Removes a notice outright: the person asked for it not to be kept. */
    suspend fun remove(id: String) {
        database.withTransaction {
            val row = dao.get(id) ?: return@withTransaction
            val now = clock()
            dao.delete(id)
            if (row.convexId == null) {
                // The server never saw it, so there is nothing to delete there; drop the
                // create that would otherwise push a row this device no longer has.
                queue.removeFor(SyncEntityType.Notice, id)
            } else {
                queue.enqueue(
                    SyncEntityType.Notice,
                    id,
                    SyncOperation.Delete,
                    SyncPayloads.noticeDelete(row.userId, now, row.convexId),
                )
            }
        }
    }

    /**
     * Enqueues a row written outside [create]/[resolve]. The operation comes from `convex_id`:
     * a row the server has never seen needs `create`, one it has needs `update`.
     */
    suspend fun enqueueExternalWrite(id: String) {
        val row = dao.get(id) ?: return
        val notice = Codecs.toNotice(row)
        queue.enqueue(
            SyncEntityType.Notice,
            id,
            if (row.convexId == null) SyncOperation.Create else SyncOperation.Update,
            SyncPayloads.notice(
                localId = notice.id,
                userId = notice.userId,
                sourceKind = notice.sourceKind,
                sourceValue = notice.sourceValue,
                title = notice.title,
                sentence = notice.sentence,
                receivedAt = notice.receivedAt,
                externalId = notice.externalId,
                resolution = notice.resolution,
                resolvedAt = notice.resolvedAt,
                createdAt = notice.createdAt,
                updatedAt = notice.updatedAt,
            ),
        )
    }

    /** Enqueues every `pending` notice with no live queue entry; returns how many. */
    suspend fun reconcilePending(): Int {
        val ids = dao.pendingWithoutQueueEntry()
        ids.forEach { enqueueExternalWrite(it) }
        return ids.size
    }

    suspend fun markSynced(id: String) = dao.setSyncStatus(id, SyncStatus.SYNCED)

    /** Puts a row that was marked `error` back to waiting, for a retry of its dead-lettered change. */
    suspend fun markPending(id: String) = dao.setSyncStatus(id, SyncStatus.PENDING)

    /** Marks the row `error` and logs why: a failed sync is never silent. */
    suspend fun markError(id: String, error: String) {
        dao.setSyncStatus(id, SyncStatus.ERROR)
        android.util.Log.e("notices", "sync error for $id: $error")
    }

    suspend fun setConvexId(localId: String, convexId: String) = dao.setConvexId(localId, convexId)

    suspend fun getConvexId(localId: String): String? = dao.get(localId)?.convexId

    suspend fun localIdForConvexId(convexId: String): String? = dao.localIdForConvexId(convexId)

    /**
     * Writes a batch from a remote pull, returning how many. Which rows are written is
     * `shouldApplyRemoteReminder` in `:core` (a Notice is a [com.taskrx.core.domain.Timestamped]
     * like any other), applied by the pull path. Marked `synced` with no enqueue - the content
     * came from the server.
     */
    suspend fun upsertMany(rows: List<PulledNotice>): Int =
        database.withTransaction {
            for ((notice, convexId) in rows) {
                dao.upsert(
                    NoticeEntity(
                        id = notice.id,
                        userId = notice.userId,
                        convexId = convexId,
                        sourceKind = notice.sourceKind.wire,
                        sourceValue = notice.sourceValue,
                        title = notice.title,
                        sentence = notice.sentence,
                        receivedAt = notice.receivedAt,
                        externalId = notice.externalId,
                        resolution = notice.resolution.wire,
                        resolvedAt = notice.resolvedAt,
                        createdAt = notice.createdAt,
                        updatedAt = notice.updatedAt,
                        syncStatus = SyncStatus.SYNCED,
                    ),
                )
            }
            rows.size
        }
}
