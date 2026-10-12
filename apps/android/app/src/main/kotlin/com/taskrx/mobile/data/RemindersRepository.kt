package com.taskrx.mobile.data

import androidx.room.withTransaction
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.mobile.data.db.ReminderEntity
import com.taskrx.mobile.data.db.SyncStatus
import com.taskrx.mobile.data.db.TaskRxDatabase

/** One field of a patch: left alone, or set to a value (which may be null, clearing it). */
sealed interface Change<out T> {
    data object Keep : Change<Nothing>

    data class To<T>(val value: T) : Change<T>
}

data class CreateReminderInput(
    /**
     * The row's id when the caller already has one to honour (the refill raise, where
     * `planRefill` has already built the whole Reminder, id included). Otherwise
     * this repository mints it.
     */
    val id: String? = null,
    val userId: String,
    val kind: ReminderKind,
    val name: String,
    val schedule: ScheduleTimes,
    val rxcui: String? = null,
    val dosage: String? = null,
    val instructions: String? = null,
    val priority: Priority? = null,
    val tags: List<String>? = null,
    val pillsRemaining: Double? = null,
    val pillsPerDose: Double? = null,
    val refillReminderDays: Int? = null,
    val caregiverIds: List<String>? = null,
)

/**
 * A patch for [RemindersRepository.update]. A [Change.To] with a null clears the
 * column; [Change.Keep] leaves it. The non-null fields use plain null for "leave".
 */
data class UpdateReminderPatch(
    val name: String? = null,
    val rxcui: Change<String?> = Change.Keep,
    val dosage: Change<String?> = Change.Keep,
    val instructions: Change<String?> = Change.Keep,
    val schedule: ScheduleTimes? = null,
    val priority: Priority? = null,
    val tags: List<String>? = null,
    val pillsRemaining: Change<Double?> = Change.Keep,
    val pillsPerDose: Change<Double?> = Change.Keep,
    val refillReminderDays: Change<Int?> = Change.Keep,
    val caregiverIds: List<String>? = null,
    val active: Boolean? = null,
)

/** A reminder as it arrives from a remote pull: the domain shape plus the two columns it does not carry. */
data class PulledReminder(
    val reminder: Reminder,
    val convexId: String,
    val rxcui: String?,
)

private fun <T> Change<T>.resolve(current: T): T =
    when (this) {
        Change.Keep -> current
        is Change.To -> value
    }

/**
 * Local repository for Reminders (data-flow rule 1: the device's store is the source
 * of truth). Mirrors apps/mobile/src/db/repos/reminders.ts.
 *
 * Every write enqueues a `sync_queue` row, in the SAME transaction as the row
 * write. The Expo repo enqueued best-effort afterwards and needed a reconcile pass
 * to recover a lost enqueue; here a failed enqueue rolls the write back, so that
 * gap cannot open. [reconcilePending] is kept anyway for rows written by pull-time
 * races and for the sync worker's drain, as there.
 *
 * What this decides is storage, not rules: whether a schedule is well formed, what
 * a Refill Reminder is, which instances are Missed all live in :core.
 */
class RemindersRepository(
    private val database: TaskRxDatabase,
    private val queue: SyncQueueRepository,
    private val clock: Clock = SystemClock,
    private val newId: IdGenerator = RandomUuid,
) {
    private val dao = database.reminderDao()

    /** Inserts a new, active Reminder and enqueues a `create`. */
    suspend fun create(input: CreateReminderInput): Reminder =
        database.withTransaction {
            val now = clock()
            val id = input.id ?: newId()
            dao.insert(
                ReminderEntity(
                    id = id,
                    userId = input.userId,
                    convexId = null,
                    kind = input.kind.wire,
                    name = input.name,
                    rxcui = input.rxcui,
                    dosage = input.dosage,
                    instructions = input.instructions,
                    schedule = Codecs.scheduleToColumn(input.schedule),
                    priority = (input.priority ?: Priority.Normal).wire,
                    tags = Codecs.stringsToColumn(input.tags.orEmpty()),
                    pillsRemaining = input.pillsRemaining,
                    pillsPerDose = input.pillsPerDose,
                    refillReminderDays = input.refillReminderDays,
                    caregiverIds = Codecs.stringsToColumn(input.caregiverIds.orEmpty()),
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                ),
            )

            // The row stores the defaults because its columns are NOT NULL; the payload
            // does not, because null there means "the caller named no value" and the
            // server supplies the same defaults itself. Sending the local default would
            // be indistinguishable from the caller choosing it.
            queue.enqueue(
                SyncEntityType.Reminder,
                id,
                SyncOperation.Create,
                SyncPayloads.reminder(
                    localId = id,
                    userId = input.userId,
                    kind = input.kind,
                    name = input.name,
                    rxcui = input.rxcui,
                    dosage = input.dosage,
                    instructions = input.instructions,
                    schedule = input.schedule,
                    priority = input.priority,
                    tags = input.tags,
                    pillsRemaining = input.pillsRemaining,
                    pillsPerDose = input.pillsPerDose,
                    refillReminderDays = input.refillReminderDays,
                    caregiverIds = input.caregiverIds,
                    active = true,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

            requireNotNull(get(id)) { "Failed to read inserted reminder" }
        }

    /**
     * A user's Reminders, newest first. [kind] restricts to one Kind; null is the
     * combined list. Stopped Reminders are left out unless [includeInactive]: the
     * timeline wants them (a stopped medication's recorded history still needs its
     * parent to render), everything that asks "what is live" does not.
     */
    suspend fun list(
        userId: String,
        kind: ReminderKind? = null,
        includeInactive: Boolean = false,
    ): List<Reminder> = dao.list(userId, kind?.wire, includeInactive).map(Codecs::toReminder)

    suspend fun get(id: String): Reminder? = dao.get(id)?.let(Codecs::toReminder)

    /** Applies [patch], bumps `updated_at`, returns the row to `pending` and enqueues an `update`. */
    suspend fun update(id: String, patch: UpdateReminderPatch): Reminder =
        database.withTransaction {
            val row = checkNotNull(dao.get(id)) { "Reminder $id not found" }
            val existing = Codecs.toReminder(row)
            val now = clock()

            val rxcui = patch.rxcui.resolve(row.rxcui)
            val next =
                existing.copy(
                    name = patch.name ?: existing.name,
                    dosage = patch.dosage.resolve(existing.dosage),
                    instructions = patch.instructions.resolve(existing.instructions),
                    schedule = patch.schedule ?: existing.schedule,
                    priority = patch.priority ?: existing.priority,
                    tags = patch.tags ?: existing.tags,
                    pillsRemaining = patch.pillsRemaining.resolve(existing.pillsRemaining),
                    pillsPerDose = patch.pillsPerDose.resolve(existing.pillsPerDose),
                    refillReminderDays = patch.refillReminderDays.resolve(existing.refillReminderDays),
                    caregiverIds = patch.caregiverIds ?: existing.caregiverIds,
                    active = patch.active ?: existing.active,
                    updatedAt = now,
                )

            dao.update(
                row.copy(
                    name = next.name,
                    rxcui = rxcui,
                    dosage = next.dosage,
                    instructions = next.instructions,
                    schedule = Codecs.scheduleToColumn(next.schedule),
                    priority = next.priority.wire,
                    tags = Codecs.stringsToColumn(next.tags),
                    pillsRemaining = next.pillsRemaining,
                    pillsPerDose = next.pillsPerDose,
                    refillReminderDays = next.refillReminderDays,
                    caregiverIds = Codecs.stringsToColumn(next.caregiverIds),
                    active = next.active,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                ),
            )

            // `patch`, not `next`, for the three server-owned fields: the patch is the
            // only evidence this caller meant to set one. The column may hold a default
            // nobody chose, and sending it would overwrite whatever the server holds on
            // every unrelated edit.
            queue.enqueue(
                SyncEntityType.Reminder,
                id,
                SyncOperation.Update,
                SyncPayloads.reminder(
                    localId = id,
                    userId = next.userId,
                    kind = next.kind,
                    name = next.name,
                    rxcui = rxcui,
                    dosage = next.dosage,
                    instructions = next.instructions,
                    schedule = next.schedule,
                    priority = patch.priority,
                    tags = patch.tags,
                    pillsRemaining = next.pillsRemaining,
                    pillsPerDose = next.pillsPerDose,
                    refillReminderDays = next.refillReminderDays,
                    caregiverIds = patch.caregiverIds,
                    active = next.active,
                    createdAt = next.createdAt,
                    updatedAt = now,
                ),
            )

            requireNotNull(get(id)) { "Reminder $id disappeared after update" }
        }

    /**
     * Stops a Reminder: `active = 0`, `updated_at` bumped, a `delete` enqueued. Never a
     * row delete - the instances' cascade would take the recorded history with it.
     * A no-op for an absent row.
     */
    suspend fun softDelete(id: String) {
        database.withTransaction {
            val row = dao.get(id) ?: return@withTransaction
            val now = clock()
            dao.update(row.copy(active = false, updatedAt = now, syncStatus = SyncStatus.PENDING))
            queue.enqueue(
                SyncEntityType.Reminder,
                id,
                SyncOperation.Delete,
                SyncPayloads.reminderDelete(id, row.userId, now),
            )
        }
    }

    /**
     * Enqueues a sync entry for a row written outside [create]/[update]. The operation
     * comes from `convex_id`: a row the server has never seen needs `create`, one it
     * has needs `update` (the worker's update branch throws on an unknown document).
     * A soft delete routes through here as an `update` carrying `active = false`.
     *
     * priority, tags and caregiverIds go as null: this is by definition the path for a
     * row no local writer authored, so reading the columns could only transmit
     * defaults, and `reminders.update` patches every field it receives.
     */
    suspend fun enqueueExternalWrite(id: String) {
        val row = dao.get(id) ?: return
        val existing = Codecs.toReminder(row)
        queue.enqueue(
            SyncEntityType.Reminder,
            id,
            if (row.convexId == null) SyncOperation.Create else SyncOperation.Update,
            SyncPayloads.reminder(
                localId = id,
                userId = existing.userId,
                kind = existing.kind,
                name = existing.name,
                rxcui = row.rxcui,
                dosage = existing.dosage,
                instructions = existing.instructions,
                schedule = existing.schedule,
                priority = null,
                tags = null,
                pillsRemaining = existing.pillsRemaining,
                pillsPerDose = existing.pillsPerDose,
                refillReminderDays = existing.refillReminderDays,
                caregiverIds = null,
                active = existing.active,
                createdAt = existing.createdAt,
                updatedAt = existing.updatedAt,
            ),
        )
    }

    /**
     * Enqueues every `pending` Reminder with no live queue entry and returns how many.
     * Dead-lettered entries do not count as live (nothing will ever drain them), and
     * `error` rows are deliberately not widened in: they would be re-enqueued, doomed,
     * on every drain.
     */
    suspend fun reconcilePending(): Int {
        val ids = dao.pendingWithoutQueueEntry()
        ids.forEach { enqueueExternalWrite(it) }
        return ids.size
    }

    suspend fun markSynced(id: String) = dao.setSyncStatus(id, SyncStatus.SYNCED)

    /** Marks the row `error` and logs why, as the Expo repo does: a failed sync is never silent. */
    suspend fun markError(id: String, error: String) {
        dao.setSyncStatus(id, SyncStatus.ERROR)
        android.util.Log.e("reminders", "sync error for $id: $error")
    }

    /** The storage-only RxNorm CUI, or null for a task or an unmatched medication. */
    suspend fun getRxcui(id: String): String? = dao.get(id)?.rxcui

    suspend fun setConvexId(localId: String, convexId: String) = dao.setConvexId(localId, convexId)

    suspend fun getConvexId(localId: String): String? = dao.get(localId)?.convexId

    /** The local id for a Convex document id, or null if the parent has not been pulled yet. */
    suspend fun localIdForConvexId(convexId: String): String? = dao.localIdForConvexId(convexId)

    /**
     * Writes a batch from a remote pull, returning how many. Every row handed here is
     * written: which rows those are is `shouldApplyRemoteReminder` in :core, applied by
     * the pull path. Marked `synced` with no enqueue - the content came from the
     * server, so re-pending it would push it straight back. `convex_id` and `kind` are
     * written from the pulled document, which is authoritative for both.
     */
    suspend fun upsertMany(rows: List<PulledReminder>): Int =
        database.withTransaction {
            for ((reminder, convexId, rxcui) in rows) {
                dao.upsert(
                    ReminderEntity(
                        id = reminder.id,
                        userId = reminder.userId,
                        convexId = convexId,
                        kind = reminder.kind.wire,
                        name = reminder.name,
                        rxcui = rxcui,
                        dosage = reminder.dosage,
                        instructions = reminder.instructions,
                        schedule = Codecs.scheduleToColumn(reminder.schedule),
                        priority = reminder.priority.wire,
                        tags = Codecs.stringsToColumn(reminder.tags),
                        pillsRemaining = reminder.pillsRemaining,
                        pillsPerDose = reminder.pillsPerDose,
                        refillReminderDays = reminder.refillReminderDays,
                        caregiverIds = Codecs.stringsToColumn(reminder.caregiverIds),
                        active = reminder.active,
                        createdAt = reminder.createdAt,
                        updatedAt = reminder.updatedAt,
                        syncStatus = SyncStatus.SYNCED,
                    ),
                )
            }
            rows.size
        }
}
