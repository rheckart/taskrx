package com.taskrx.mobile.sync

import android.util.Log
import com.taskrx.core.domain.Notice
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.core.sync.backoffMillis
import com.taskrx.core.sync.shouldApplyRemoteReminder
import com.taskrx.core.sync.shouldApplyRemoteReminderInstance
import com.taskrx.mobile.data.NoticesRepository
import com.taskrx.mobile.data.PulledNotice
import com.taskrx.mobile.data.PulledReminder
import com.taskrx.mobile.data.PulledReminderInstance
import com.taskrx.mobile.data.PulledWatchedSource
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.data.WatchSourcesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Merges the server's copy into the local store. Mirrors `applyRemotePull` and
 * `useUnifiedSync` in archive/expo-rn:apps/mobile/src/sync/pull.ts.
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
    private val notices: NoticesRepository,
    private val watchSources: WatchSourcesRepository,
    /**
     * Called with the notices this pass wrote (a new one, or one that changed). The heads-up
     * ping is posted here, where a notice's arrival is known, rather than from a screen.
     */
    private val onNoticesApplied: suspend (List<Notice>) -> Unit = {},
    /**
     * Called after Reminders were written. A Reminder created or edited on another device (or
     * restored on first sign-in) must be armed now, not at the next foreground: the foreground
     * pass at sign-in runs before the first pull has landed.
     */
    private val onRemindersApplied: suspend () -> Unit = {},
) {
    internal suspend fun apply(
        remoteReminders: List<ServerReminder>,
        remoteInstances: List<ServerInstance>,
        remoteWatchSources: List<ServerWatchedSource> = emptyList(),
        remoteNotices: List<ServerNotice> = emptyList(),
    ) {
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
        if (applicable.isNotEmpty()) {
            try {
                onRemindersApplied()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "rescheduling after pull failed", e)
            }
        }

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
            // The safety-critical skip: a dose recorded as taken here is replaced only by the person's
            // own undo, and a change not pushed yet is never replaced at all.
            if (shouldApplyRemoteReminderInstance(local, candidate.instance, instances.hasUnpushedChange(candidate.instance.id))) resolved += candidate
        }
        instances.upsertMany(resolved)

        // Watched sources first: they are what admits a notice, so a phone that pulls both
        // has the gate in place before the notices it would have let through.
        val appliedSources = ArrayList<PulledWatchedSource>()
        for (doc in remoteWatchSources) {
            val candidate = ServerDocs.toPulled(doc) ?: continue
            if (shouldApplyRemoteReminder(watchSources.get(candidate.source.id), candidate.source)) appliedSources += candidate
        }
        watchSources.upsertMany(appliedSources)

        val appliedNotices = ArrayList<PulledNotice>()
        for (doc in remoteNotices) {
            val candidate = ServerDocs.toPulled(doc) ?: continue
            if (shouldApplyRemoteReminder(notices.get(candidate.notice.id), candidate.notice)) appliedNotices += candidate
        }
        notices.upsertMany(appliedNotices)
        if (appliedNotices.isNotEmpty()) {
            try {
                onNoticesApplied(appliedNotices.map { it.notice })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "pinging a pulled notice failed", e)
            }
        }
    }

    /** One pass over the four lists as the gateway delivered them. */
    suspend fun applyJson(
        remoteReminders: JsonElement,
        remoteInstances: JsonElement,
        remoteWatchSources: JsonElement = Json.parseToJsonElement("[]"),
        remoteNotices: JsonElement = Json.parseToJsonElement("[]"),
    ) = apply(
        ServerDocs.reminders(remoteReminders),
        ServerDocs.instances(remoteInstances),
        ServerDocs.watchSources(remoteWatchSources),
        ServerDocs.notices(remoteNotices),
    )

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
 *
 * [reapply] re-runs the last delivered pass. A pass refuses a row that has a change waiting
 * to be pushed, and the server's answer to that push can arrive before the push worker has
 * cleared the entry (the Convex client applies query updates before it resolves the
 * mutation). If the server refused the push and kept another device's answer (ADR 0018),
 * no later delivery need carry that row again, so the coordinator calls this after every
 * drain and the row converges on what was already delivered.
 */
class PullSync(
    private val gateway: ConvexGateway,
    private val applier: PullApplier,
    private val retryPause: suspend (Long) -> Unit = { delay(it) },
) {
    private val passes = Mutex()
    private var last: Quad? = null

    /** Runs the last delivered pass again, or nothing when none has arrived yet. */
    suspend fun reapply() {
        passes.withLock {
            val quad = last ?: return
            pass(quad)
        }
    }

    private suspend fun pass(quad: Quad) {
        try {
            applier.applyJson(quad.reminders, quad.instances, quad.watchSources, quad.notices)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "pull failed", e)
        }
    }

    suspend fun run() {
        combine(
            gateway.subscribe(ConvexFunctions.REMINDERS_LIST),
            gateway.subscribe(ConvexFunctions.INSTANCES_LIST),
            gateway.subscribe(ConvexFunctions.WATCH_SOURCES_LIST),
            gateway.subscribe(ConvexFunctions.NOTICES_LIST),
        ) { remoteReminders, remoteInstances, remoteWatchSources, remoteNotices ->
            Quad(remoteReminders, remoteInstances, remoteWatchSources, remoteNotices)
        }
            .conflate()
            .retryWhen { cause, attempt ->
                if (cause is CancellationException) return@retryWhen false
                Log.e(TAG, "subscription failed", cause)
                retryPause(backoffMillis(attempt.toInt() + 1))
                true
            }
            .collect { quad ->
                passes.withLock {
                    last = quad
                    pass(quad)
                }
            }
    }

    /** Four delivered lists, held together so the combine has one value. */
    private data class Quad(
        val reminders: JsonElement,
        val instances: JsonElement,
        val watchSources: JsonElement,
        val notices: JsonElement,
    )

    private companion object {
        const val TAG = "pull"
    }
}
