package com.taskrx.core.sync

/*
 * What the header's sync badge shows, ported from `useSyncStatus` in
 * `archive/expo-rn:apps/mobile/src/sync/pull.ts`.
 */

/** What the push worker last did. */
enum class SyncWorkerStatus { Idle, Syncing, Error, Offline }

enum class SyncBadge { Synced, Pending, Syncing, Offline, Error }

/**
 * The badge for the current moment.
 *
 * Offline wins over everything - a signed-out device, or one with no connection, is not
 * "behind", it is not trying. After that a running drain is Syncing; then a stuck change is
 * Error, and it stays Error for as long as it is stuck - [hasStuck] is true while any entry has
 * been dead-lettered, because the worker's own status goes back to Idle on the next quiet pass
 * and a dose that failed for good would read Synced. Then unpushed work is Pending, and a
 * failed drain with nothing left waiting is Error. Otherwise the device is Synced.
 *
 * [connected] is null until the first connectivity reading, which is not evidence of being
 * offline.
 */
fun syncBadge(
    signedIn: Boolean,
    connected: Boolean?,
    worker: SyncWorkerStatus,
    hasPending: Boolean,
    hasStuck: Boolean,
): SyncBadge =
    when {
        !signedIn -> SyncBadge.Offline
        connected == false -> SyncBadge.Offline
        worker == SyncWorkerStatus.Offline -> SyncBadge.Offline
        worker == SyncWorkerStatus.Syncing -> SyncBadge.Syncing
        hasStuck -> SyncBadge.Error
        hasPending -> SyncBadge.Pending
        worker == SyncWorkerStatus.Error -> SyncBadge.Error
        else -> SyncBadge.Synced
    }
