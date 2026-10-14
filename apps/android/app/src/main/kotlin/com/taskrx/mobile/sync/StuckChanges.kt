package com.taskrx.mobile.sync

import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.data.SyncEntityType
import com.taskrx.mobile.data.SyncQueueEntry
import com.taskrx.mobile.data.SyncQueueRepository
import com.taskrx.mobile.format.formatDayTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One change that failed for good: what it was in words, and why the server would not take it. */
data class StuckChange(val entryId: Long, val what: String, val error: String)

/**
 * The changes the push worker gave up on after five tries, for the signed-in user.
 *
 * They are kept, never deleted: a recorded dose is not data to lose. Without a way back they
 * stayed on the device for good, and the sync badge went back to Synced once the worker went
 * quiet. [retryAll] puts each back to waiting with a clean count and its row back to
 * `pending`, in the order they were written, so a dose whose Reminder also failed is sent after
 * it. The coordinator sees the queue grow and drains.
 */
class StuckChanges(
    private val queue: SyncQueueRepository,
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val userId: () -> String?,
) {
    private suspend fun mine(): List<SyncQueueEntry> {
        val user = userId() ?: return emptyList()
        return queue.deadLettered().filter { it.isOwnedBy(user) }
    }

    suspend fun count(): Int = mine().size

    suspend fun list(): List<StuckChange> = mine().map { StuckChange(it.id, describe(it), it.lastError ?: "Unknown error") }

    /** Returns how many were put back. */
    suspend fun retryAll(): Int {
        val stuck = mine()
        for (entry in stuck) {
            when (entry.entityType) {
                SyncEntityType.Reminder -> reminders.markPending(entry.entityId)
                SyncEntityType.ReminderInstance -> instances.markPending(entry.entityId)
            }
            queue.requeue(entry.id)
        }
        return stuck.size
    }

    private suspend fun describe(entry: SyncQueueEntry): String {
        val payload = runCatching { Json.parseToJsonElement(entry.payload) as? JsonObject }.getOrNull()
        return when (entry.entityType) {
            SyncEntityType.Reminder -> {
                val name = payload.text("name") ?: reminders.get(entry.entityId)?.name ?: "a reminder"
                "Reminder: $name"
            }
            SyncEntityType.ReminderInstance -> {
                val name = payload.text("reminderId")?.let { reminders.get(it)?.name } ?: "a reminder"
                val time = (payload?.get("scheduledTime") as? JsonPrimitive)?.longOrNull
                if (time == null) "Answer to $name" else "Answer to $name, due ${formatDayTime(time)}"
            }
        }
    }

    private fun JsonObject?.text(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}
