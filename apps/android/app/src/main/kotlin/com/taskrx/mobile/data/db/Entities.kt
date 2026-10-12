package com.taskrx.mobile.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a row stands with the server. Stored as the same three words the Expo
 * schema's CHECK admitted, so the columns read the same in a database dump.
 */
object SyncStatus {
    const val PENDING = "pending"
    const val SYNCED = "synced"
    const val ERROR = "error"
}

/** Table `reminders`, docs/architecture.md. */
@Entity(
    tableName = "reminders",
    indices = [
        Index(value = ["user_id", "active"]),
        Index(value = ["user_id", "kind"]),
        Index(value = ["convex_id"]),
        Index(value = ["sync_status"]),
    ],
)
data class ReminderEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "convex_id") val convexId: String?,
    val kind: String,
    val name: String,
    /** Storage-only RxNorm CUI; not on the domain Reminder. */
    val rxcui: String?,
    val dosage: String?,
    val instructions: String?,
    /** JSON `ScheduleTimes`, as :core writes it (`serializeScheduleTimes`). */
    val schedule: String,
    val priority: String,
    /** JSON string array. */
    val tags: String,
    @ColumnInfo(name = "pills_remaining") val pillsRemaining: Double?,
    @ColumnInfo(name = "pills_per_dose") val pillsPerDose: Double?,
    @ColumnInfo(name = "refill_reminder_days") val refillReminderDays: Int?,
    /** JSON string array. */
    @ColumnInfo(name = "caregiver_ids") val caregiverIds: String,
    val active: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
)

/**
 * Table `reminder_instances`. Deleting a Reminder row cascades here, which is why
 * the repository never hard-deletes one: that would take the history with it.
 */
@Entity(
    tableName = "reminder_instances",
    foreignKeys = [
        ForeignKey(
            entity = ReminderEntity::class,
            parentColumns = ["id"],
            childColumns = ["reminder_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["reminder_id"]),
        Index(value = ["user_id", "scheduled_time"]),
        Index(value = ["user_id", "status", "scheduled_time"]),
        Index(value = ["convex_id"]),
        Index(value = ["sync_status"]),
    ],
)
data class ReminderInstanceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "reminder_id") val reminderId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "convex_id") val convexId: String?,
    /** Denormalized from the parent so Adherence filters without a join. */
    @ColumnInfo(name = "reminder_kind") val reminderKind: String,
    @ColumnInfo(name = "scheduled_time") val scheduledTime: Long,
    val status: String,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: Long?,
    @ColumnInfo(name = "snooze_count") val snoozeCount: Int,
    val notes: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "sync_status") val syncStatus: String,
)

/** Table `sync_queue`. Dead-lettered rows are retained, never deleted. */
@Entity(
    tableName = "sync_queue",
    indices = [Index(value = ["entity_type", "entity_id", "dead_letter"])],
)
data class SyncQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "entity_type") val entityType: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    val operation: String,
    /** JSON snapshot at enqueue time. */
    val payload: String,
    val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "dead_letter") val deadLetter: Boolean,
)
