package com.taskrx.mobile.sync

import com.taskrx.core.sync.SyncBadge
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val pull: PullSync,
    private val connectivity: Connectivity,
    private val pendingPollMs: Long = PENDING_POLL_MS,
) {
    private val hasPending = MutableStateFlow(false)
    private var syncJob: Job? = null

    val badge: StateFlow<SyncBadge> =
        combine(auth.status, connectivity.connected, worker.status, hasPending) { status, connected, workerStatus, pending ->
            syncBadge(status is AuthStatus.SignedIn, connected, workerStatus, pending)
        }.stateIn(scope, SharingStarted.Eagerly, SyncBadge.Offline)

    fun start() {
        scope.launch {
            combine(auth.status, ready) { status, isReady -> status is AuthStatus.SignedIn && isReady }
                .collectLatest { active ->
                    if (active) runSync() else hasPending.value = false
                }
        }
    }

    fun onForeground() {
        if (syncJob?.isActive == true) scope.launch { worker.drain() }
    }

    private suspend fun runSync() {
        coroutineScope {
            syncJob = coroutineContext[Job]
            launch { pull.run() }
            launch {
                connectivity.connected.collectLatest { connected ->
                    if (connected == false) worker.markOffline() else worker.drain()
                }
            }
            launch {
                var seen = 0
                while (true) {
                    val count = worker.pendingCount()
                    hasPending.value = count > 0
                    // A write made while online goes up now, not at the next foreground. Only
                    // a rise drains: an entry that keeps failing does not grow the count, so
                    // this cannot cut short the worker's backoff.
                    if (count > seen && connectivity.connected.value != false) launch { worker.drain() }
                    seen = count
                    delay(pendingPollMs)
                }
            }
        }
    }

    private companion object {
        const val PENDING_POLL_MS = 5_000L
    }
}
