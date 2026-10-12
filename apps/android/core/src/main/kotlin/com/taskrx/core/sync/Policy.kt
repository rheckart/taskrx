package com.taskrx.core.sync

/*
 * The push worker's retry policy, ported from `archive/expo-rn:apps/mobile/src/sync/worker.ts`.
 *
 * Kept here, not in the worker, so the numbers and the arithmetic are one tested thing: a
 * failed write is retried with a growing pause and, after the fifth failure, parked in the
 * dead-letter state, where it stays for inspection (data-flow rule 6: a record of a dose
 * taken is never silently dropped).
 */

/** The fifth failed attempt dead-letters an entry. */
const val MAX_ATTEMPTS: Int = 5

const val BACKOFF_BASE_MS: Long = 1_000

const val BACKOFF_MAX_MS: Long = 30_000

/**
 * The pause after the [attempts]th failure of an entry: 1s, 2s, 4s, 8s, 16s, then 30s
 * for ever. [attempts] counts the failure just recorded, so it is at least 1; a smaller
 * value is treated as the first.
 */
fun backoffMillis(attempts: Int): Long {
    // Capped before shifting: 2^20 * 1s is far past the ceiling, and a larger shift
    // would overflow a Long rather than saturate.
    val exponent = (attempts - 1).coerceIn(0, 20)
    return minOf(BACKOFF_BASE_MS shl exponent, BACKOFF_MAX_MS)
}

/**
 * Whether an entry that has failed [attemptsBefore] times and has just failed once more
 * must now be dead-lettered.
 */
fun isDeadLetterDue(attemptsBefore: Int): Boolean = attemptsBefore + 1 >= MAX_ATTEMPTS
