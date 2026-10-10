package com.taskrx.mobile.sync

import android.util.Log
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.core.sync.PushCall
import com.taskrx.core.sync.PushRejected
import com.taskrx.core.sync.SyncWorkerStatus
import com.taskrx.core.sync.backoffMillis
import com.taskrx.core.sync.instanceCreateCall
import com.taskrx.core.sync.instanceUpdateCall
import com.taskrx.core.sync.isDeadLetterDue
import com.taskrx.core.sync.reminderCreateCall
import com.taskrx.core.sync.reminderDeleteCall
import com.taskrx.core.sync.reminderUpdateCall
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.data.SyncEntityType
import com.taskrx.mobile.data.SyncOperation
import com.taskrx.mobile.data.SyncQueueEntry
import com.taskrx.mobile.data.SyncQueueRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The push half of sync: drains the local `sync_queue` into the Convex mutations. Mirrors
 * apps/mobile/src/sync/worker.ts.
 *
 * Which call an entry becomes, the retry limit and the backoff are :core's
 * (`com.taskrx.core.sync`); this class does the I/O around them. The local UUID <-> server
 * id mapping lives in the `convex_id` columns, because the server assigns its own `_id` on
 * insert.
 *
 * One drain at a time ([drain] returns at once if one is running). A failed entry is counted
 * ([SyncQueueRepository.markFailed]), the drain pauses for the backoff, and on the fifth
 * failure the entry is dead-lettered and its source row marked `error`; nothing is ever
 * deleted on failure.
 *
 * [userId] is whose entries this device may push. An entry written for any other user (the
 * signed-out local user) is left in the queue untouched: the server would refuse it
 * ("Cannot create reminder for another user") and the refusals would dead-letter data that is
 * perfectly good locally.
 */
class PushWorker(
    private val queue: SyncQueueRepository,
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val gateway: ConvexGateway,
    private val connectivity: Connectivity,
    private val userId: () -> String?,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    private val running = Mutex()
    private val state = MutableStateFlow(SyncWorkerStatus.Idle)

    val status: StateFlow<SyncWorkerStatus> = state.asStateFlow()

    /** Called when the connection drops, so the badge says so without waiting for a drain. */
    fun markOffline() {
        state.value = SyncWorkerStatus.Offline
    }

    /** Entries waiting to be pushed for the current user. Dead-lettered ones are not "waiting". */
    suspend fun pendingCount(): Int {
        val user = userId() ?: return 0
        return queue.pending().count { it.isOwnedBy(user) }
    }

    suspend fun drain() {
        // Claimed before the first suspension: two callers (foreground and the connectivity
        // listener) must not both pass the guard and drain the same snapshot.
        if (!running.tryLock()) return
        try {
            drainLocked()
        } catch (e: CancellationException) {
            // Interrupted (connectivity flipped, signed out): not syncing any more, and not a failure.
            state.value = SyncWorkerStatus.Idle
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "drain failed", e)
            state.value = SyncWorkerStatus.Error
        } finally {
            // Every path out releases it, offline and failed included. A flag left set would
            // make the NEXT drain silently do nothing.
            running.unlock()
        }
    }

    private suspend fun drainLocked() {
        val user = userId()
        if (user == null) {
            state.value = SyncWorkerStatus.Idle
            return
        }
        val connected =
            try {
                connectivity.isConnected()
            } catch (e: Exception) {
                Log.e(TAG, "connectivity check failed", e)
                state.value = SyncWorkerStatus.Error
                return
            }
        if (!connected) {
            state.value = SyncWorkerStatus.Offline
            return
        }
        state.value = SyncWorkerStatus.Syncing

        // Rows that are pending but have no queue entry (a best-effort enqueue that failed
        // after its row write) would otherwise never be pushed. Failing here must not cost
        // the drain: the queue as it stands is still worth draining.
        try {
            reminders.reconcilePending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "reminder reconciliation failed", e)
        }
        try {
            instances.reconcilePending()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "instance reconciliation failed", e)
        }

        var failed = false
        for (entry in queue.pending()) {
            if (!entry.isOwnedBy(user)) continue
            val error = attempt(entry)
            if (error == null) {
                queue.remove(entry.id)
                markSourceSynced(entry)
                continue
            }
            if (NOT_AUTHENTICATED in error) {
                // The server refused the caller, not the entry: right after a reconnect the
                // socket is back before the token is. Spending an attempt on that would let a
                // slow re-authentication dead-letter a recorded dose. Nothing behind this entry
                // would fare better, so the pass stops; the coordinator retries an Error drain.
                failed = true
                state.value = SyncWorkerStatus.Error
                pause(backoffMillis(1))
                break
            }
            queue.markFailed(entry.id, error)
            if (isDeadLetterDue(entry.attempts)) {
                markSourceError(entry, error)
                queue.markDeadLettered(entry.id)
            }
            failed = true
            state.value = SyncWorkerStatus.Error
            pause(backoffMillis(entry.attempts + 1))
        }
        // From what THIS pass did, not a fresh read of the queue: work enqueued while the
        // loop ran is the next drain's, not a failure of this one.
        state.value = if (failed) SyncWorkerStatus.Error else SyncWorkerStatus.Idle
    }

    /** The failure message, or null when the entry was pushed. */
    private suspend fun attempt(entry: SyncQueueEntry): String? =
        try {
            push(entry)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: e.toString()
        }

    private suspend fun push(entry: SyncQueueEntry) {
        val payload = Json.parseToJsonElement(entry.payload) as? JsonObject ?: throw PushRejected("Payload is not an object")
        when (entry.entityType) {
            SyncEntityType.Reminder -> pushReminder(entry, payload)
            SyncEntityType.ReminderInstance -> pushInstance(entry, payload)
        }
    }

    private suspend fun pushReminder(entry: SyncQueueEntry, payload: JsonObject) {
        when (entry.operation) {
            SyncOperation.Create -> {
                val id = idOf(send(reminderCreateCall(payload)))
                reminders.setConvexId(entry.entityId, id)
            }
            SyncOperation.Update -> send(reminderUpdateCall(payload, resolveReminderId(entry.entityId)))
            SyncOperation.Delete -> send(reminderDeleteCall(resolveReminderId(entry.entityId)))
        }
    }

    private suspend fun pushInstance(entry: SyncQueueEntry, payload: JsonObject) {
        when (entry.operation) {
            SyncOperation.Create -> {
                // The parent has to exist on the server first: the mutation takes its id and
                // reads the Kind off it.
                val parent = resolveReminderId(payload.string("reminderId"))
                val id = idOf(send(instanceCreateCall(payload, parent)))
                instances.setConvexId(entry.entityId, id)
            }
            SyncOperation.Update -> send(instanceUpdateCall(payload, resolveInstanceId(entry.entityId)))
            // The repository never deletes an instance; a removal is a status change.
            SyncOperation.Delete -> throw PushRejected("Unknown operation delete")
        }
    }

    private suspend fun send(call: PushCall): JsonElement = gateway.mutation(call.function, call.args)

    /**
     * A local reminder id as the server's. The cache is `reminders.convex_id`, written only
     * from ids a mutation or query returned; a miss asks the server by `localId`.
     */
    private suspend fun resolveReminderId(localId: String): String {
        reminders.getConvexId(localId)?.let { return it }
        val remote = gateway.query(ConvexFunctions.REMINDERS_GET_BY_LOCAL_ID, mapOf("localId" to localId))
        if (remote is JsonNull) throw IllegalStateException("Reminder $localId not found on Convex")
        return documentId(remote).also { reminders.setConvexId(localId, it) }
    }

    private suspend fun resolveInstanceId(localId: String): String {
        instances.getConvexId(localId)?.let { return it }
        val remote = gateway.query(ConvexFunctions.INSTANCES_GET_BY_LOCAL_ID, mapOf("localId" to localId))
        if (remote is JsonNull) throw IllegalStateException("Reminder instance $localId not found on Convex")
        return documentId(remote).also { instances.setConvexId(localId, it) }
    }

    // Switches, not an if/else: the next entity type added must not fall into the wrong repo,
    // whose UPDATE would match no row and leave sync_status stuck at `pending`.
    private suspend fun markSourceSynced(entry: SyncQueueEntry) {
        try {
            when (entry.entityType) {
                SyncEntityType.Reminder -> reminders.markSynced(entry.entityId)
                SyncEntityType.ReminderInstance -> instances.markSynced(entry.entityId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "failed to mark source synced", e)
        }
    }

    private suspend fun markSourceError(entry: SyncQueueEntry, error: String) {
        try {
            when (entry.entityType) {
                SyncEntityType.Reminder -> reminders.markError(entry.entityId, error)
                SyncEntityType.ReminderInstance -> instances.markError(entry.entityId, error)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "failed to mark source error", e)
        }
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw PushRejected("Payload is missing $key")

    private fun idOf(response: JsonElement): String =
        (response as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalStateException("Expected an id from the server, got $response")

    private fun documentId(document: JsonElement): String =
        ((document as? JsonObject)?.get("_id") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalStateException("Expected a document with an _id, got $document")

    private companion object {
        const val TAG = "pushWorker"

        /** What `getCurrentUserId` in packages/convex/convex/auth.ts throws with no valid token. */
        const val NOT_AUTHENTICATED = "Not authenticated"
    }
}

/** An entry is the user's when its payload names them; one that cannot be read is theirs to fail loudly. */
internal fun SyncQueueEntry.isOwnedBy(user: String): Boolean {
    val owner =
        try {
            (Json.parseToJsonElement(payload) as? JsonObject)?.get("userId") as? JsonPrimitive
        } catch (_: IllegalArgumentException) {
            null
        }
    return owner == null || owner.content == user
}
