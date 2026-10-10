package com.taskrx.mobile.sync

import android.util.Log
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.core.sync.backoffMillis
import com.taskrx.core.sync.shouldApplyRemoteReminder
import com.taskrx.core.sync.shouldApplyRemoteReminderInstance
import com.taskrx.mobile.data.PulledReminder
import com.taskrx.mobile.data.PulledReminderInstance
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import kotlinx.serialization.json.JsonElement

/**
 * Merges the server's copy into the local store. Mirrors `applyRemotePull` and
 * `useUnifiedSync` in apps/mobile/src/sync/pull.ts.
 *
 * The conflict decisions are :core's (`shouldApplyRemoteReminder`,
 * `shouldApplyRemoteReminderInstance`): local wins ties, and a locally Completed instance is
 * never overwritten. The repositories only write what they are handed.
 *
 * Reminders go first and are awaited, because an instance names its parent by server id and
 * the lookup reads the table the reminder write fills; on a fresh install every instance
 * would otherwise find no parent. An instance whose parent is not local (soft-deleted on the
 * server, which `reminders.list` filters out) is skipped, not written with a foreign key it
 * cannot honour.
 */
class PullApplier(
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
) {
    internal suspend fun apply(remoteReminders: List<ServerReminder>, remoteInstances: List<ServerInstance>) {
        val applicable = ArrayList<PulledReminder>()
        for (doc in remoteReminders) {
            val candidate = ServerDocs.toPulled(doc)
            if (candidate == null) {
                Log.w(TAG, "reminder ${doc.localId} skipped: unknown kind ${doc.kind}")
                continue
            }
            val local = reminders.get(candidate.reminder.id)
            if (shouldApplyRemoteReminder(local, candidate.reminder)) applicable += candidate
        }
        reminders.upsertMany(applicable)

        val resolved = ArrayList<PulledReminderInstance>()
        for (doc in remoteInstances) {
            val localReminderId = reminders.localIdForConvexId(doc.reminderId)
            if (localReminderId == null) {
                Log.w(TAG, "instance ${doc.localId} skipped: reminder ${doc.reminderId} not pulled")
                continue
            }
            val candidate = ServerDocs.toPulled(doc, localReminderId)
            if (candidate == null) {
                Log.w(TAG, "instance ${doc.localId} skipped: unknown kind or status")
                continue
            }
            val local = instances.get(candidate.instance.id)
            // The safety-critical skip: a dose recorded as taken here is never replaced.
            if (shouldApplyRemoteReminderInstance(local, candidate.instance)) resolved += candidate
        }
        instances.upsertMany(resolved)
    }

    /** One pass over the two lists as the gateway delivered them. */
    suspend fun applyJson(remoteReminders: JsonElement, remoteInstances: JsonElement) =
        apply(ServerDocs.reminders(remoteReminders), ServerDocs.instances(remoteInstances))

    private companion object {
        const val TAG = "pull"
    }
}

/**
 * The two live subscriptions, one ordered pass. Any delivery of either re-runs the whole
 * pass: that is what retries an instance skipped for want of its parent, and it is cheap and
 * idempotent because a row already at the same `updatedAt` is not written again.
 *
 * A failed pass (bad document, database error) is logged and the next delivery tries again;
 * a failed subscription (dropped, server error) is resubscribed after a backoff.
 */
class PullSync(
    private val gateway: ConvexGateway,
    private val applier: PullApplier,
    private val retryPause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run() {
        combine(
            gateway.subscribe(ConvexFunctions.REMINDERS_LIST),
            gateway.subscribe(ConvexFunctions.INSTANCES_LIST),
        ) { remoteReminders, remoteInstances -> remoteReminders to remoteInstances }
            .conflate()
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                Log.e(TAG, "subscription failed", cause)
                retryPause(backoffMillis(attempt.toInt() + 1))
                true
            }
            .collect { (remoteReminders, remoteInstances) ->
                try {
                    applier.applyJson(remoteReminders, remoteInstances)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "pull failed", e)
                }
            }
    }

    private companion object {
        const val TAG = "pull"
    }
}
