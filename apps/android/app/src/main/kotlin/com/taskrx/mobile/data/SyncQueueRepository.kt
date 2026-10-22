package com.taskrx.mobile.data

import com.taskrx.mobile.data.db.SyncQueueDao
import com.taskrx.mobile.data.db.SyncQueueEntity
import kotlinx.serialization.json.JsonObject

/** The entity kinds the queue accepts. */
enum class SyncEntityType(val wire: String) {
    Reminder("reminder"),
    ReminderInstance("reminder_instance"),
}

enum class SyncOperation(val wire: String) {
    Create("create"),
    Update("update"),
    Delete("delete"),
}

data class SyncQueueEntry(
    val id: Long,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperation,
    /** JSON snapshot taken when the write happened. */
    val payload: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val deadLetter: Boolean,
)

/**
 * The append-only log of writes waiting to be pushed (data-flow rule 6).
 *
 * Dead-lettered rows are retained, never deleted: a dropped record of a medication
 * taken is unacceptable data loss. The retry limit and backoff are the sync
 * worker's (phase 5); this repository only keeps the state they act on.
 */
class SyncQueueRepository(
    private val dao: SyncQueueDao,
    private val clock: Clock = SystemClock,
) {
    suspend fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: JsonObject,
    ) {
        dao.insert(
            SyncQueueEntity(
                entityType = entityType.wire,
                entityId = entityId,
                operation = operation.wire,
                payload = payload.toString(),
                attempts = 0,
                lastError = null,
                createdAt = clock(),
                deadLetter = false,
            ),
        )
    }

    /** Entries still to be drained, oldest first; dead-lettered rows are excluded. */
    suspend fun pending(): List<SyncQueueEntry> = dao.pending().map(::toEntry)

    /** Terminally failed entries, for inspection and manual requeue. */
    suspend fun deadLettered(): List<SyncQueueEntry> = dao.deadLettered().map(::toEntry)

    suspend fun remove(id: Long) = dao.remove(id)

    /**
     * Whether a change to one entity is still waiting to be pushed. Dead-lettered entries count:
     * a change the server has refused is still the newest thing the person did, and a pull must
     * not quietly replace it (it is listed in Sync problems until they decide).
     */
    suspend fun hasEntriesFor(entityType: SyncEntityType, entityId: String): Boolean = dao.countFor(entityType.wire, entityId) > 0

    /** Counts one more failed attempt. Does not dead-letter; the worker decides when. */
    suspend fun markFailed(id: Long, error: String) = dao.markFailed(id, error)

    suspend fun markDeadLettered(id: Long) = dao.markDeadLettered(id)

    /** Re-arms a dead-lettered entry so the next drain retries it. */
    suspend fun requeue(id: Long) = dao.requeue(id)

    private fun toEntry(row: SyncQueueEntity): SyncQueueEntry =
        SyncQueueEntry(
            id = row.id,
            entityType = SyncEntityType.entries.first { it.wire == row.entityType },
            entityId = row.entityId,
            operation = SyncOperation.entries.first { it.wire == row.operation },
            payload = row.payload,
            attempts = row.attempts,
            lastError = row.lastError,
            createdAt = row.createdAt,
            deadLetter = row.deadLetter,
        )
}
