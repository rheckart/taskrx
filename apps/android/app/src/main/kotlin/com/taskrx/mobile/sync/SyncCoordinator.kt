package com.taskrx.mobile.sync

import com.taskrx.core.sync.SyncBadge
import com.taskrx.core.sync.SyncWorkerStatus
import com.taskrx.core.sync.syncBadge
import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.auth.AuthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * Starts and stops sync with the sign-in state, and publishes the badge.
 *
 * Sync runs only while signed in AND [ready] (the server holds a valid token): the pull is
 * subscribed and the push drained on connectivity regained and when new work is queued.
 * A foreground also drains ([onForeground]). Signing out cancels it all; the local store
 * is left as it is.
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val auth: AuthGateway,
    private val ready: StateFlow<Boolean>,
    private val worker: PushWorker,
    private val stuck: StuckChanges,
    private val pull: PullSync,
    private val connectivity: Connectivity,
    private val pendingPollMs: Long = PENDING_POLL_MS,
) {
    private val hasPending = MutableStateFlow(false)
    private val hasStuck = MutableStateFlow(false)
    private var syncJob: Job? = null

    val badge: StateFlow<SyncBadge> =
        combine(auth.status, connectivity.connected, worker.status, hasPending, hasStuck) { status, connected, workerStatus, pending, isStuck ->
            syncBadge(status is AuthStatus.SignedIn, connected, workerStatus, pending, isStuck)
        }.stateIn(scope, SharingStarted.Eagerly, SyncBadge.Offline)

    fun start() {
        scope.launch {
            combine(auth.status, ready) { status, isReady -> status is AuthStatus.SignedIn && isReady }
                .collectLatest { active ->
                    // Signed in but between tokens is not "nothing waiting": the badge must not
                    // read Synced over a dose that is still queued while the client logs in again.
                    if (active) {
                        runSync()
                    } else if (auth.status.value !is AuthStatus.SignedIn) {
                        hasPending.value = false
                        hasStuck.value = false
                    }
                }
        }
    }

    fun onForeground() {
        if (syncJob?.isActive == true) scope.launch { drain() }
    }

    private suspend fun runSync() {
        coroutineScope {
            syncJob = coroutineContext[Job]
            launch { pull.run() }
            launch {
                connectivity.connected.collectLatest { connected ->
                    if (connected == false) worker.markOffline() else drain()
                }
            }
            launch {
                var seen = 0
                while (true) {
                    val count = worker.pendingCount()
                    hasPending.value = count > 0
                    hasStuck.value = stuck.count() > 0
                    // A write made while online goes up now, not at the next foreground. Only
                    // a rise drains: an entry that keeps failing does not grow the count, so
                    // this cannot cut short the worker's backoff.
                    //
                    // A drain that ended in Error is run again as long as something waits: the
                    // first push after a reconnect can reach the server before the Convex
                    // client has re-authenticated ("Not authenticated"), fails once, and
                    // nothing else would ever look at the queue again until a foreground. The
                    // worker's own backoff (it holds the drain while it pauses) spaces the
                    // retries, and the fifth failure dead-letters, so this does not spin.
                    val retry = count > 0 && worker.status.value == SyncWorkerStatus.Error
                    if ((count > seen || retry) && connectivity.connected.value != false) launch { drain() }
                    seen = count
                    delay(pendingPollMs)
                }
            }
        }
    }

    /**
     * One push and one pull, for the background job (ADR 0016): desktop edits reach the phone's
     * alarms with the app closed. Waits for sign-in to finish starting and for the server to
     * hold a token, each within [readyTimeoutMs], since a process woken for this job starts both
     * from cold. The pull re-arms through its applier, as a live delivery does.
     */
    suspend fun pullOnce(readyTimeoutMs: Long = READY_TIMEOUT_MS): BackgroundPull {
        val status = withTimeoutOrNull(readyTimeoutMs) { auth.status.first { it !is AuthStatus.Loading } } ?: return BackgroundPull.NotReady
        if (status !is AuthStatus.SignedIn) return BackgroundPull.SignedOut
        withTimeoutOrNull(readyTimeoutMs) { ready.first { it } } ?: return BackgroundPull.NotReady
        worker.drain()
        return if (pull.once()) BackgroundPull.Done else BackgroundPull.Failed
    }

    /** A drain, then the last pull pass again, so a row the push refused converges ([PullSync.reapply]). */
    private suspend fun drain() {
        worker.drain()
        pull.reapply()
    }

    private companion object {
        const val PENDING_POLL_MS = 5_000L

        /** Long enough for Clerk and the Convex client to start in a cold process. */
        const val READY_TIMEOUT_MS = 30_000L
    }
}

/** How one background pull ended. */
enum class BackgroundPull {
    /** Pushed what was waiting and pulled the four lists. */
    Done,

    /** Nobody is signed in: nothing to pull, and nothing to try again. */
    SignedOut,

    /** Sign-in or the server's token did not come up in time. */
    NotReady,

    /** A read or the pass failed. */
    Failed,
}
