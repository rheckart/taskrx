package com.taskrx.mobile.data

import androidx.room.withTransaction
import com.taskrx.mobile.data.db.TaskRxDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hands everything written while signed out to the account that signs in.
 *
 * Signed out, rows are written under the local user, and the push worker leaves their queue
 * entries alone because the server would refuse a row for another user. Without this they
 * would sit on the device for good, invisible once signed in. On sign-in the Reminders, the
 * Reminder Instances and the queued payloads move to [to] in one transaction, and the next
 * drain pushes them as the creates they always were. The payloads are rewritten in place, so
 * an entry keeps its operation, its order and every field it was enqueued with.
 *
 * Whoever signs in next takes them: there is one device and one person, and the local rows
 * belong to no account until then. Returns how many Reminders moved.
 */
class LocalAdoption(private val database: TaskRxDatabase) {
    suspend fun adopt(from: String, to: String): Int {
        if (from == to) return 0
        return database.withTransaction {
            val reminders = database.reminderDao().reassignUser(from, to)
            database.reminderInstanceDao().reassignUser(from, to)
            // In Kotlin, not SQL: json_set is not in every SQLite Android ships.
            val queue = database.syncQueueDao()
            for (entry in queue.all()) {
                val payload = Json.parseToJsonElement(entry.payload) as? JsonObject ?: continue
                if ((payload["userId"] as? JsonPrimitive)?.content != from) continue
                queue.setPayload(entry.id, JsonObject(payload + ("userId" to JsonPrimitive(to))).toString())
            }
            reminders
        }
    }
}
