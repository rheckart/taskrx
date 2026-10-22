package com.taskrx.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ReminderEntity)

    @Update
    suspend fun update(row: ReminderEntity)

    /**
     * Insert or overwrite by id: the shape a remote pull needs. `@Upsert` and not
     * REPLACE, because REPLACE deletes the row first and the instances' cascading
     * foreign key would take the history with it.
     */
    @Upsert
    suspend fun upsert(row: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: String): ReminderEntity?

    /** `kind` null means both Kinds; newest first. */
    @Query(
        """
        SELECT * FROM reminders
        WHERE user_id = :userId
          AND (:includeInactive = 1 OR active = 1)
          AND (:kind IS NULL OR kind = :kind)
        ORDER BY created_at DESC, id ASC
        """,
    )
    suspend fun list(userId: String, kind: String?, includeInactive: Boolean): List<ReminderEntity>

    @Query("SELECT id FROM reminders WHERE convex_id = :convexId")
    suspend fun localIdForConvexId(convexId: String): String?

    @Query("UPDATE reminders SET sync_status = :status WHERE id = :id")
    suspend fun setSyncStatus(id: String, status: String)

    @Query("UPDATE reminders SET convex_id = :convexId WHERE id = :id")
    suspend fun setConvexId(id: String, convexId: String)

    @Query("UPDATE reminders SET user_id = :to WHERE user_id = :from")
    suspend fun reassignUser(from: String, to: String): Int

    /** Pending rows with no live queue entry to make them un-pend. */
    @Query(
        """
        SELECT r.id FROM reminders r
        WHERE r.sync_status = 'pending'
          AND NOT EXISTS (
            SELECT 1 FROM sync_queue q
            WHERE q.entity_type = 'reminder' AND q.entity_id = r.id AND q.dead_letter = 0
          )
        """,
    )
    suspend fun pendingWithoutQueueEntry(): List<String>
}

@Dao
interface ReminderInstanceDao {
    @Query("UPDATE reminder_instances SET user_id = :to WHERE user_id = :from")
    suspend fun reassignUser(from: String, to: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: ReminderInstanceEntity)

    @Update
    suspend fun update(row: ReminderInstanceEntity)

    @Upsert
    suspend fun upsert(row: ReminderInstanceEntity)

    @Query("SELECT * FROM reminder_instances WHERE id = :id")
    suspend fun get(id: String): ReminderInstanceEntity?

    /** Every filter is optional (null); newest scheduled time first. */
    @Query(
        """
        SELECT * FROM reminder_instances
        WHERE user_id = :userId
          AND (:reminderId IS NULL OR reminder_id = :reminderId)
          AND (:reminderKind IS NULL OR reminder_kind = :reminderKind)
          AND (:since IS NULL OR scheduled_time >= :since)
          AND (:until IS NULL OR scheduled_time <= :until)
          AND (:status IS NULL OR status = :status)
        ORDER BY scheduled_time DESC, id ASC
        """,
    )
    suspend fun list(
        userId: String,
        reminderId: String?,
        reminderKind: String?,
        since: Long?,
        until: Long?,
        status: String?,
    ): List<ReminderInstanceEntity>

    @Query(
        """
        SELECT * FROM reminder_instances
        WHERE user_id = :userId AND status = 'pending' AND scheduled_time <= :through
        ORDER BY scheduled_time ASC, id ASC
        """,
    )
    suspend fun pendingThrough(userId: String, through: Long): List<ReminderInstanceEntity>

    /** [statuses] is the answerable set from :core, passed in rather than spelled here. */
    @Query(
        """
        SELECT * FROM reminder_instances
        WHERE user_id = :userId AND reminder_id = :reminderId AND scheduled_time = :scheduledTime
          AND status IN (:statuses)
        ORDER BY created_at DESC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findForOccurrence(
        userId: String,
        reminderId: String,
        scheduledTime: Long,
        statuses: List<String>,
    ): ReminderInstanceEntity?

    /** Every Instance at one exact moment of one Reminder, whatever its status. */
    @Query(
        """
        SELECT * FROM reminder_instances
        WHERE user_id = :userId AND reminder_id = :reminderId AND scheduled_time = :scheduledTime
        ORDER BY created_at DESC, id ASC
        """,
    )
    suspend fun allForOccurrence(userId: String, reminderId: String, scheduledTime: Long): List<ReminderInstanceEntity>

    @Query(
        """
        SELECT * FROM reminder_instances
        WHERE user_id = :userId AND reminder_id = :reminderId AND status = 'completed'
        ORDER BY completed_at DESC, scheduled_time DESC, id ASC
        LIMIT 1
        """,
    )
    suspend fun lastCompletedFor(userId: String, reminderId: String): ReminderInstanceEntity?

    @Query("UPDATE reminder_instances SET sync_status = :status WHERE id = :id")
    suspend fun setSyncStatus(id: String, status: String)

    @Query("UPDATE reminder_instances SET convex_id = :convexId WHERE id = :id")
    suspend fun setConvexId(id: String, convexId: String)

    @Query(
        """
        SELECT i.id FROM reminder_instances i
        WHERE i.sync_status = 'pending'
          AND NOT EXISTS (
            SELECT 1 FROM sync_queue q
            WHERE q.entity_type = 'reminder_instance' AND q.entity_id = i.id AND q.dead_letter = 0
          )
        """,
    )
    suspend fun pendingWithoutQueueEntry(): List<String>
}

@Dao
interface SyncQueueDao {
    @Insert
    suspend fun insert(row: SyncQueueEntity): Long

    @Query("SELECT * FROM sync_queue WHERE dead_letter = 0 ORDER BY created_at ASC, id ASC")
    suspend fun pending(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE dead_letter = 1 ORDER BY created_at ASC, id ASC")
    suspend fun deadLettered(): List<SyncQueueEntity>

    @Query("DELETE FROM sync_queue WHERE id = :id")
    suspend fun remove(id: Long)

    @Query("UPDATE sync_queue SET attempts = attempts + 1, last_error = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("UPDATE sync_queue SET dead_letter = 1 WHERE id = :id")
    suspend fun markDeadLettered(id: Long)

    @Query("UPDATE sync_queue SET dead_letter = 0, attempts = 0, last_error = NULL WHERE id = :id")
    suspend fun requeue(id: Long)

    @Query("SELECT * FROM sync_queue ORDER BY id ASC")
    suspend fun all(): List<SyncQueueEntity>

    /** Entries of one entity still in the queue, dead-lettered ones included. */
    @Query("SELECT COUNT(*) FROM sync_queue WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun countFor(entityType: String, entityId: String): Int

    @Query("UPDATE sync_queue SET payload = :payload WHERE id = :id")
    suspend fun setPayload(id: Long, payload: String)
}
