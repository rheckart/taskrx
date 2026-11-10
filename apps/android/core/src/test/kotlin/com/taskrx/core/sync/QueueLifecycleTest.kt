package com.taskrx.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QueueLifecycleTest {
    @Test
    fun `only a dead-lettered row is DeadLettered`() {
        assertEquals(QueuePhase.Waiting, queuePhase(deadLetter = false))
        assertEquals(QueuePhase.DeadLettered, queuePhase(deadLetter = true))
    }

    @Test
    fun `the first four failures retry with a growing pause`() {
        val expectedPauses = listOf(1_000L, 2_000L, 4_000L, 8_000L)

        for ((before, pause) in expectedPauses.withIndex()) {
            val outcome = afterFailure(before)

            assertIs<QueueFailureOutcome.Retry>(outcome, "failure ${before + 1}")
            assertEquals(before + 1, outcome.attempts)
            assertEquals(pause, outcome.pauseMs)
        }
    }

    @Test
    fun `the fifth failure dead-letters, and so does every one after`() {
        val fifth = afterFailure(4)

        assertIs<QueueFailureOutcome.DeadLetter>(fifth)
        assertEquals(5, fifth.attempts)
        assertEquals(16_000L, fifth.pauseMs)

        val later = afterFailure(9)
        assertIs<QueueFailureOutcome.DeadLetter>(later)
        assertEquals(10, later.attempts)
        // The pause saturates at the ceiling rather than overflowing.
        assertEquals(BACKOFF_MAX_MS, later.pauseMs)
    }
}