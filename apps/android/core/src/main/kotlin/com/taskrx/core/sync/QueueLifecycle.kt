package com.taskrx.core.sync

/*
 * The explicit lifecycle of one sync-queue entry.
 *
 * The queue stores only two durable phases: Waiting, with the number of attempts so far,
 * and DeadLettered, where it is kept for inspection and manual requeue (data-flow rule 6).
 * "In flight" is deliberately not stored: it is the push worker's mutex, not a fact about
 * the row, so a crash mid-drain leaves the entry Waiting rather than stranded in a state
 * nothing will pick up.
 *
 * The decision after a failed attempt used to live in `PushWorker` as an `if` around
 * `isDeadLetterDue` and a second, separately written `backoffMillis` call. Here it is one
 * value: the next phase, the attempt count, and how long to wait before trying again.
 */

/** The persisted phase of a queued change. */
enum class QueuePhase {
    /** Eligible for the next drain. [attempts] says how many failures it has survived. */
    Waiting,

    /** Failed for good; retained, never deleted, until the person retries it. */
    DeadLettered,
}

/** What a failed attempt means for the entry. */
sealed interface QueueFailureOutcome {
    /** The attempt count after this failure. */
    val attempts: Int

    /** How long the drain waits before looking at the queue again. */
    val pauseMs: Long

    /** Still Waiting: the next drain tries it again. */
    data class Retry(override val attempts: Int, override val pauseMs: Long) : QueueFailureOutcome

    /** The fifth failure: move it to [QueuePhase.DeadLettered] and mark its source row `error`. */
    data class DeadLetter(override val attempts: Int, override val pauseMs: Long) : QueueFailureOutcome
}

/** The phase a stored `dead_letter` flag represents. */
fun queuePhase(deadLetter: Boolean): QueuePhase = if (deadLetter) QueuePhase.DeadLettered else QueuePhase.Waiting

/**
 * What happens after an entry that had [attemptsBefore] failures fails once more.
 *
 * The fifth failure dead-letters it ([isDeadLetterDue]); every earlier one retries after
 * the growing backoff ([backoffMillis]). The pause is returned for the dead-letter case
 * too, because the drain has to wait somewhere and the next entry should not be hammered
 * the instant a permanent failure is recorded.
 */
fun afterFailure(attemptsBefore: Int): QueueFailureOutcome {
    val attempts = attemptsBefore + 1
    val pauseMs = backoffMillis(attempts)
    return if (isDeadLetterDue(attemptsBefore)) {
        QueueFailureOutcome.DeadLetter(attempts, pauseMs)
    } else {
        QueueFailureOutcome.Retry(attempts, pauseMs)
    }
}