package com.taskrx.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolicyTest {
    @Test
    fun `backs off 1s 2s 4s 8s 16s then holds at 30s`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(::backoffMillis))
    }

    @Test
    fun `treats a count below one as the first failure`() {
        assertEquals(1_000L, backoffMillis(0))
        assertEquals(1_000L, backoffMillis(-3))
    }

    @Test
    fun `saturates rather than overflowing for a huge count`() {
        assertEquals(30_000L, backoffMillis(Int.MAX_VALUE))
    }

    @Test
    fun `dead-letters on the fifth failure and not before`() {
        assertEquals(5, MAX_ATTEMPTS)
        assertFalse(isDeadLetterDue(0))
        assertFalse(isDeadLetterDue(3))
        assertTrue(isDeadLetterDue(4))
        assertTrue(isDeadLetterDue(9))
    }
}

class SyncBadgeTest {
    private fun badge(
        signedIn: Boolean = true,
        connected: Boolean? = true,
        worker: SyncWorkerStatus = SyncWorkerStatus.Idle,
        pending: Boolean = false,
        stuck: Boolean = false,
    ) = syncBadge(signedIn, connected, worker, pending, stuck)

    @Test
    fun `is synced when signed in, connected, idle and nothing waits`() {
        assertEquals(SyncBadge.Synced, badge())
    }

    @Test
    fun `an unknown connection is not offline`() {
        assertEquals(SyncBadge.Synced, badge(connected = null))
    }

    @Test
    fun `signed out is offline`() {
        assertEquals(SyncBadge.Offline, badge(signedIn = false))
    }

    @Test
    fun `no connection is offline even with work waiting`() {
        assertEquals(SyncBadge.Offline, badge(connected = false, pending = true))
    }

    @Test
    fun `a worker that found itself offline is offline`() {
        assertEquals(SyncBadge.Offline, badge(worker = SyncWorkerStatus.Offline))
    }

    @Test
    fun `a running drain is syncing`() {
        assertEquals(SyncBadge.Syncing, badge(worker = SyncWorkerStatus.Syncing, pending = true))
    }

    @Test
    fun `unpushed work is pending, even after a failed drain`() {
        assertEquals(SyncBadge.Pending, badge(pending = true))
        assertEquals(SyncBadge.Pending, badge(worker = SyncWorkerStatus.Error, pending = true))
    }

    @Test
    fun `a change that failed for good stays an error after the worker goes quiet`() {
        assertEquals(SyncBadge.Error, badge(stuck = true))
        assertEquals(SyncBadge.Error, badge(stuck = true, pending = true))
        assertEquals(SyncBadge.Error, badge(stuck = true, worker = SyncWorkerStatus.Idle))
    }

    @Test
    fun `offline and syncing still outrank a stuck change`() {
        assertEquals(SyncBadge.Offline, badge(stuck = true, connected = false))
        assertEquals(SyncBadge.Offline, badge(stuck = true, signedIn = false))
        assertEquals(SyncBadge.Syncing, badge(stuck = true, worker = SyncWorkerStatus.Syncing))
    }

    @Test
    fun `a failed drain with nothing left waiting is an error`() {
        assertEquals(SyncBadge.Error, badge(worker = SyncWorkerStatus.Error))
    }
}
