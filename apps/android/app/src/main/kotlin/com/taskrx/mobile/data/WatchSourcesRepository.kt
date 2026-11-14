package com.taskrx.mobile.data

import androidx.room.withTransaction
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.WatchedSource
import com.taskrx.mobile.data.db.SyncStatus
import com.taskrx.mobile.data.db.TaskRxDatabase
import com.taskrx.mobile.data.db.WatchedSourceEntity

data class CreateWatchedSourceInput(
    val userId: String,
    val kind: SourceKind,
    val value: String,
    val label: String,
)

/** A watched source as it arrives from a remote pull. */
data class PulledWatchedSource(
    val source: WatchedSource,
    val convexId: String,
)

/**
 * Local repository for Watched sources: the senders and apps whose notices may be kept at
 * all.
 *
 * The rule that a share is never a watched source lives on the server
 * (`watchSources.create` refuses `kind: "share"`); this repository does not duplicate it,
 * because a share arrives through the share road and is never written here.
 */
class WatchSourcesRepository(
    private val database: TaskRxDatabase,
    private val queue: SyncQueueRepository,
    private val clock: Clock = SystemClock,
    private val newId: IdGenerator = RandomUuid,
) {
    private val dao = database.watchedSourceDao()

    /** Adds a source and enqueues a `create`. */
    suspend fun create(input: CreateWatchedSourceInput): WatchedSource =
        database.withTransaction {
            val now = clock()
            val id = newId()
            val row =
                WatchedSourceEntity(
                    id = id,
                    userId = input.userId,
                    convexId = null,
                    kind = input.kind.wire,
                    value = input.value,
                    label = input.label,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                )
            dao.insert(row)
            queue.enqueue(
                SyncEntityType.WatchSource,
                id,
                SyncOperation.Create,
                SyncPayloads.watchSource(
                    localId = id,
                    userId = row.userId,
                    kind = input.kind,
                    value = row.value,
                    label = row.label,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                ),
            )
            Codecs.toWatchedSource(row)
        }

    suspend fun list(userId: String): List<WatchedSource> = dao.list(userId).map(Codecs::toWatchedSource)

    suspend fun get(id: String): WatchedSource? = dao.get(id)?.let(Codecs::toWatchedSource)

    /** Renames a source. Its address or package is identity and is not rewritten. */
    suspend fun updateLabel(id: String, label: String): WatchedSource =
        database.withTransaction {
            val row = checkNotNull(dao.get(id)) { "Watched source $id not found" }
            val now = clock()
            val next = row.copy(label = label, updatedAt = now, syncStatus = SyncStatus.PENDING)
            dao.update(next)
            queue.enqueue(
                SyncEntityType.WatchSource,
                id,
                SyncOperation.Update,
                SyncPayloads.watchSource(
                    localId = next.id,
                    userId = next.userId,
                    kind = SourceKind.fromWire(next.kind) ?: SourceKind.Email,
                    value = next.value,
                    label = next.label,
                    createdAt = next.createdAt,
                    updatedAt = next.updatedAt,
                ),
            )
            Codecs.toWatchedSource(next)
        }

    /** Stops watching a source. Notices already kept stay: they are evidence of what arrived. */
    suspend fun remove(id: String) {
        database.withTransaction {
            val row = dao.get(id) ?: return@withTransaction
            val now = clock()
            dao.delete(id)
            if (row.convexId == null) {
                queue.removeFor(SyncEntityType.WatchSource, id)
            } else {
                queue.enqueue(
                    SyncEntityType.WatchSource,
                    id,
                    SyncOperation.Delete,
                    SyncPayloads.watchSourceDelete(row.userId, now, row.convexId),
                )
            }
        }
    }

    /** Enqueues a row written outside [create]/[updateLabel], choosing the operation from `convex_id`. */
    suspend fun enqueueExternalWrite(id: String) {
        val row = dao.get(id) ?: return
        queue.enqueue(
            SyncEntityType.WatchSource,
            id,
            if (row.convexId == null) SyncOperation.Create else SyncOperation.Update,
            SyncPayloads.watchSource(
                localId = row.id,
                userId = row.userId,
                kind = SourceKind.fromWire(row.kind) ?: SourceKind.Email,
                value = row.value,
                label = row.label,
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
            ),
        )
    }

    /** Enqueues every `pending` source with no live queue entry; returns how many. */
    suspend fun reconcilePending(): Int {
        val ids = dao.pendingWithoutQueueEntry()
        ids.forEach { enqueueExternalWrite(it) }
        return ids.size
    }

    suspend fun markSynced(id: String) = dao.setSyncStatus(id, SyncStatus.SYNCED)

    suspend fun markPending(id: String) = dao.setSyncStatus(id, SyncStatus.PENDING)

    suspend fun markError(id: String, error: String) {
        dao.setSyncStatus(id, SyncStatus.ERROR)
        android.util.Log.e("watchSources", "sync error for $id: $error")
    }

    suspend fun setConvexId(localId: String, convexId: String) = dao.setConvexId(localId, convexId)

    suspend fun getConvexId(localId: String): String? = dao.get(localId)?.convexId

    suspend fun localIdForConvexId(convexId: String): String? = dao.localIdForConvexId(convexId)

    /** Writes a batch from a remote pull, returning how many. Marked `synced` with no enqueue. */
    suspend fun upsertMany(rows: List<PulledWatchedSource>): Int =
        database.withTransaction {
            for ((source, convexId) in rows) {
                dao.upsert(
                    WatchedSourceEntity(
                        id = source.id,
                        userId = source.userId,
                        convexId = convexId,
                        kind = source.kind.wire,
                        value = source.value,
                        label = source.label,
                        createdAt = source.createdAt,
                        updatedAt = source.updatedAt,
                        syncStatus = SyncStatus.SYNCED,
                    ),
                )
            }
            rows.size
        }
}
