package com.taskrx.mobile.data

import androidx.room.withTransaction
import com.taskrx.core.domain.ANSWERABLE_STATUSES
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sync.shouldApplyRemoteReminderInstance
import com.taskrx.mobile.data.db.ReminderInstanceEntity
import com.taskrx.mobile.data.db.SyncStatus
import com.taskrx.mobile.data.db.TaskRxDatabase

data class CreateReminderInstanceInput(
    val userId: String,
    val reminderId: String,
    val reminderKind: ReminderKind,
    val scheduledTime: Long,
    /**
     * What the row starts as. `Pending` is materializing an occurrence someone is about
     * to answer. Two callers open an already-settled row, and neither is a user
     * answering a prompt: the missed sweep mints `Missed`, and the Backlog mints
     * `Completed` (ADR 0002). Both decide the value in :core.
     */
    val status: InstanceStatus = InstanceStatus.Pending,
    val completedAt: Long? = null,
)

data class InstanceFilter(
    val userId: String,
    val reminderId: String? = null,
    val reminderKind: ReminderKind? = null,
    val since: Long? = null,
    val until: Long? = null,
    val status: InstanceStatus? = null,
)

/** An instance from a remote pull. `instance.reminderId` is already the LOCAL reminder id. */
data class PulledReminderInstance(
    val instance: ReminderInstance,
    val convexId: String,
)

/**
 * Local repository for Reminder Instances: the record of what became of one Occurrence.
 * Mirrors apps/mobile/src/db/repos/reminderInstances.ts.
 *
 * Append-only history: nothing here deletes a row. A "deletion" is a status change,
 * and the `update` entry it enqueues carries it. Writes and their enqueue share one
 * transaction (see [RemindersRepository]).
 */
class ReminderInstancesRepository(
    private val database: TaskRxDatabase,
    private val queue: SyncQueueRepository,
    private val clock: Clock = SystemClock,
    private val newId: IdGenerator = RandomUuid,
) {
    private val dao = database.reminderInstanceDao()

    /** Materializes an Instance - `Pending` unless told otherwise - and enqueues a `create`. */
    suspend fun create(input: CreateReminderInstanceInput): ReminderInstance =
        database.withTransaction {
            val now = clock()
            val id = newId()
            dao.insert(
                ReminderInstanceEntity(
                    id = id,
                    reminderId = input.reminderId,
                    userId = input.userId,
                    convexId = null,
                    reminderKind = input.reminderKind.wire,
                    scheduledTime = input.scheduledTime,
                    status = input.status.wire,
                    completedAt = input.completedAt,
                    snoozedUntil = null,
                    snoozeCount = 0,
                    notes = null,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                ),
            )
            queue.enqueue(
                SyncEntityType.ReminderInstance,
                id,
                SyncOperation.Create,
                SyncPayloads.instance(
                    localId = id,
                    userId = input.userId,
                    reminderId = input.reminderId,
                    reminderKind = input.reminderKind,
                    scheduledTime = input.scheduledTime,
                    status = input.status,
                    completedAt = input.completedAt,
                    snoozedUntil = null,
                    snoozeCount = 0,
                    notes = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            requireNotNull(get(id)) { "Failed to read inserted reminder instance" }
        }

    /** Instances matching [filter], newest scheduled time first. */
    suspend fun list(filter: InstanceFilter): List<ReminderInstance> =
        dao.list(
            userId = filter.userId,
            reminderId = filter.reminderId,
            reminderKind = filter.reminderKind?.wire,
            since = filter.since,
            until = filter.until,
            status = filter.status?.wire,
        ).map(Codecs::toInstance)

    suspend fun get(id: String): ReminderInstance? = dao.get(id)?.let(Codecs::toInstance)

    /** Pending instances at or before [through], oldest first. */
    suspend fun pendingThrough(userId: String, through: Long): List<ReminderInstance> =
        dao.pendingThrough(userId, through).map(Codecs::toInstance)

    private suspend fun updateStatus(
        id: String,
        status: InstanceStatus,
        completedAt: Long? = null,
        snoozedUntil: Long? = null,
        notes: String? = null,
        incrementSnooze: Boolean = false,
    ): ReminderInstance =
        database.withTransaction {
            val existing = checkNotNull(dao.get(id)) { "Reminder instance $id not found" }
            val now = clock()
            val updated =
                existing.copy(
                    status = status.wire,
                    completedAt = completedAt,
                    snoozedUntil = snoozedUntil,
                    snoozeCount = existing.snoozeCount + if (incrementSnooze) 1 else 0,
                    notes = notes ?: existing.notes,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                )
            dao.update(updated)

            // Read off the post-write row: snooze_count is an increment and notes falls
            // back to the stored value, so the row is the only place the truth exists.
            queue.enqueue(
                SyncEntityType.ReminderInstance,
                id,
                SyncOperation.Update,
                payloadOf(updated, updatedAt = now),
            )
            Codecs.toInstance(updated)
        }

    /**
     * Completed - the one word for both Kinds. A medication is not "taken" here; the
     * screens say "Taken" (ADR 0001).
     */
    suspend fun markCompleted(id: String, completedAt: Long, notes: String? = null): ReminderInstance =
        updateStatus(id, InstanceStatus.Completed, completedAt = completedAt, notes = notes)

    /** Snoozed until [snoozedUntil]; counts one more snooze. Neither a skip nor a miss. */
    suspend fun markSnoozed(id: String, snoozedUntil: Long): ReminderInstance =
        updateStatus(id, InstanceStatus.Snoozed, snoozedUntil = snoozedUntil, incrementSnooze = true)

    suspend fun markSkipped(id: String, notes: String? = null): ReminderInstance =
        updateStatus(id, InstanceStatus.Skipped, notes = notes)

    /**
     * The sweep's verdict, not a user action. Only an unanswered (`pending`) row becomes
     * Missed: the sweep plans from a snapshot, and the person may answer between that read
     * and this write. The check is inside the transaction, so an answer that landed first
     * is returned untouched rather than overwritten.
     */
    suspend fun markMissed(id: String): ReminderInstance =
        database.withTransaction {
            val existing = checkNotNull(dao.get(id)) { "Reminder instance $id not found" }
            if (existing.status != InstanceStatus.Pending.wire) {
                Codecs.toInstance(existing)
            } else {
                updateStatus(id, InstanceStatus.Missed)
            }
        }

    /**
     * The still-answerable Instance for an exact Reminder and moment, if one has already
     * been materialized - so a notification tap, or a second visit to the same moment,
     * does not open a duplicate. Which statuses are answerable is
     * `ANSWERABLE_STATUSES` in :core.
     */
    suspend fun findAnswerableForOccurrence(
        userId: String,
        reminderId: String,
        scheduledTime: Long,
    ): ReminderInstance? =
        dao.findForOccurrence(userId, reminderId, scheduledTime, ANSWERABLE_STATUSES.map { it.wire })
            ?.let(Codecs::toInstance)

    /** The most recent completed Instance for a Reminder, or null if never completed. */
    suspend fun lastCompletedFor(userId: String, reminderId: String): ReminderInstance? =
        dao.lastCompletedFor(userId, reminderId)?.let(Codecs::toInstance)

    /**
     * Enqueues a row written outside [create]/[updateStatus]. The operation comes from
     * `convex_id` (see [RemindersRepository.enqueueExternalWrite]); there is no delete
     * case because an Instance is never deleted.
     */
    suspend fun enqueueExternalWrite(id: String) {
        val row = dao.get(id) ?: return
        queue.enqueue(
            SyncEntityType.ReminderInstance,
            id,
            if (row.convexId == null) SyncOperation.Create else SyncOperation.Update,
            payloadOf(row, updatedAt = row.updatedAt),
        )
    }

    /** Enqueues every `pending` Instance with no live queue entry; returns how many. */
    suspend fun reconcilePending(): Int {
        val ids = dao.pendingWithoutQueueEntry()
        ids.forEach { enqueueExternalWrite(it) }
        return ids.size
    }

    suspend fun markSynced(id: String) = dao.setSyncStatus(id, SyncStatus.SYNCED)

    /** Marks the row `error` and logs why, as the Expo repo does: a failed sync is never silent. */
    suspend fun markError(id: String, error: String) {
        dao.setSyncStatus(id, SyncStatus.ERROR)
        android.util.Log.e("reminderInstances", "sync error for $id: $error")
    }

    suspend fun setConvexId(localId: String, convexId: String) = dao.setConvexId(localId, convexId)

    suspend fun getConvexId(localId: String): String? = dao.get(localId)?.convexId

    /**
     * Writes a batch from a remote pull, returning how many. The safety-critical rule
     * that decides which rows - a locally completed Instance is never overwritten - is
     * `shouldApplyRemoteReminderInstance` in :core, applied by the pull path. `synced`,
     * no enqueue; `reminder_kind` is taken from the remote row (the server's copy is
     * authoritative). Parents must already exist: the foreign key refuses an orphan.
     */
    suspend fun upsertMany(rows: List<PulledReminderInstance>): Int =
        database.withTransaction {
            for ((instance, convexId) in rows) {
                // Decided again, inside the transaction: the pull judged these rows against a
                // read taken before it, and an answer recorded in between must still be safe.
                if (!shouldApplyRemoteReminderInstance(dao.get(instance.id)?.let(Codecs::toInstance), instance)) continue
                dao.upsert(
                    ReminderInstanceEntity(
                        id = instance.id,
                        reminderId = instance.reminderId,
                        userId = instance.userId,
                        convexId = convexId,
                        reminderKind = instance.reminderKind.wire,
                        scheduledTime = instance.scheduledTime,
                        status = instance.status.wire,
                        completedAt = instance.completedAt,
                        snoozedUntil = instance.snoozedUntil,
                        snoozeCount = instance.snoozeCount,
                        notes = instance.notes,
                        createdAt = instance.createdAt,
                        updatedAt = instance.updatedAt,
                        syncStatus = SyncStatus.SYNCED,
                    ),
                )
            }
            rows.size
        }

    private fun payloadOf(row: ReminderInstanceEntity, updatedAt: Long) =
        SyncPayloads.instance(
            localId = row.id,
            userId = row.userId,
            reminderId = row.reminderId,
            reminderKind = ReminderKind.fromWire(row.reminderKind) ?: ReminderKind.Task,
            scheduledTime = row.scheduledTime,
            status = InstanceStatus.fromWire(row.status) ?: InstanceStatus.Pending,
            completedAt = row.completedAt,
            snoozedUntil = row.snoozedUntil,
            snoozeCount = row.snoozeCount,
            notes = row.notes,
            createdAt = row.createdAt,
            updatedAt = updatedAt,
        )
}
