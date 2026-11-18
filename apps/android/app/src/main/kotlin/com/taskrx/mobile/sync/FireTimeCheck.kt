package com.taskrx.mobile.sync

import android.os.SystemClock
import android.util.Log
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.isMomentAnswered
import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.data.RemindersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** What the check at fire time found. Only [Answered] keeps a dose quiet; every other outcome posts it. */
enum class FireCheck {
    /** The server holds rows for the moment and every one is an answer: taken or skipped elsewhere. */
    Answered,

    /** The server has no answer for the moment, or an open row. */
    Open,

    /** Nobody is signed in, so there is no server to ask. */
    SignedOut,

    /** The reminder has never reached the server, so no other device can have answered it. */
    NotOnServer,

    /** The answer did not come in time. */
    TimedOut,

    /** The check threw: no network, a refused token, a broken store. */
    Failed,

    /** A build with no server. */
    LocalOnly,
}

/**
 * How a dose reads its moment: given the read itself, it hands back the rows, from that read or
 * from one already made for another dose due at the same instant (see [FireGate]).
 */
internal typealias MomentRead = suspend (read: suspend () -> List<ServerInstance>) -> List<ServerInstance>

/** A read made for this dose alone. */
internal val readAlone: MomentRead = { read -> read() }

/**
 * Asks the server, as a dose's alarm fires, whether that moment was already answered on another
 * device (ADR 0016). Unbounded by itself: [FireGate] bounds it and posts the dose when it is slow.
 *
 * It reads the one instant through `reminderInstances:list` (`since` = `until`, on the
 * `by_user_time` index; [MomentReader], over HTTP rather than the app's Convex connection) and
 * keeps the rows of this reminder, found by its server id. The read covers every reminder at that
 * instant, so the doses due together can share it ([MomentRead]).
 */
internal class FireTimeCheck(
    private val auth: AuthGateway,
    private val reminders: RemindersRepository,
    private val read: suspend (scheduledTime: Long) -> List<ServerInstance>,
) {
    suspend fun check(reminderId: String, scheduledTime: Long): FireCheck = check(reminderId, scheduledTime, readAlone)

    internal suspend fun check(reminderId: String, scheduledTime: Long, moment: MomentRead): FireCheck {
        val status = auth.status.first { it !is AuthStatus.Loading }
        if (status !is AuthStatus.SignedIn) return FireCheck.SignedOut
        val serverId = reminders.getConvexId(reminderId) ?: return FireCheck.NotOnServer
        val rows = moment { read(scheduledTime) }.filter { it.reminderId == serverId && it.scheduledTime.toLong() == scheduledTime }
        val statuses = rows.mapNotNull { InstanceStatus.fromWire(it.status) }
        // A row this build cannot read is not an answer it can trust.
        if (statuses.size != rows.size) return FireCheck.Open
        return if (isMomentAnswered(statuses)) FireCheck.Answered else FireCheck.Open
    }
}

/**
 * The bound on the check at fire time. It fails open (ADR 0016): a check that is slow, throws, or
 * cannot run leaves the dose to post as it always has. A Companion may quiet an alarm only by a
 * positive answer from the server, never by being unreachable.
 *
 * The bound is per moment, not per dose. Android hands a process its alarms one at a time and
 * holds the next until the last one's receiver is done, so a wait per dose would make five doses
 * at 07:30 with no network post over twenty seconds. Instead the first dose at an instant starts
 * the moment's clock and its one read; a dose due at the same instant shares that read and gets
 * what is left of the bound, at least [LATE_FLOOR_MS] to look up its own reminder.
 *
 * The check runs in a scope of its own and the wait is what times out, not the work: building the
 * app graph and loading the Convex library block their thread and ignore cancellation, so a
 * timeout placed around them would wait for them anyway. The shared read outlives a dose that
 * gave up on it, so a later dose still waiting can use it; it is dropped after [SHARE_MS].
 */
class FireGate(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private class Moment(val opened: Long) {
        var read: Deferred<List<ServerInstance>?>? = null
    }

    private val moments = HashMap<Long, Moment>()

    internal suspend fun decide(
        moment: Long,
        timeoutMs: Long = TIMEOUT_MS,
        check: suspend (MomentRead) -> FireCheck,
    ): FireCheck {
        val entry = open(moment)
        val budget = maxOf(entry.opened + timeoutMs - clock(), LATE_FLOOR_MS)
        val shared: MomentRead = { read -> start(entry, read).await() ?: error("the read of $moment was abandoned") }
        val answer =
            work.async {
                try {
                    check(shared)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // An Error too: a native library that will not load must not cost a dose.
                    Log.w(TAG, "fire-time check failed", t)
                    FireCheck.Failed
                }
            }
        val outcome =
            withTimeoutOrNull(budget) {
                try {
                    answer.await()
                } catch (e: CancellationException) {
                    // The wait running out cancels this block: rethrown, and the dose times out.
                    // Anything else cancelled the check itself, which is no answer either.
                    currentCoroutineContext().ensureActive()
                    FireCheck.Failed
                }
            }
        if (outcome == null) answer.cancel()
        return outcome ?: FireCheck.TimedOut
    }

    /** The moment's entry, opened now if no dose has asked about it in the last [SHARE_MS]. */
    @Synchronized
    private fun open(moment: Long): Moment {
        val now = clock()
        val stale = moments.filterValues { now - it.opened >= SHARE_MS }
        for ((key, old) in stale) {
            old.read?.cancel()
            moments.remove(key)
        }
        return moments.getOrPut(moment) { Moment(now) }
    }

    /** The moment's one read, started by the first dose to need it. */
    @Synchronized
    private fun start(entry: Moment, read: suspend () -> List<ServerInstance>): Deferred<List<ServerInstance>?> =
        entry.read ?: work.async { withTimeoutOrNull(SHARE_MS) { read() } }.also { entry.read = it }

    companion object {
        /** Long enough for a cold start to sign in and read one instant; short next to a dose. */
        const val TIMEOUT_MS = 4_000L

        /** How long one read serves the doses of its instant, and how long it may run. */
        const val SHARE_MS = 30_000L

        /** What a dose that arrives after the moment's bound still gets, to match its rows. */
        const val LATE_FLOOR_MS = 250L

        /** The gate of this process: the alarms of one instant all pass through it. */
        val process = FireGate()

        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

private const val TAG = "alarm"
