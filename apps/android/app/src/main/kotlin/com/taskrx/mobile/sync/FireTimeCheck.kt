package com.taskrx.mobile.sync

import android.util.Log
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.isMomentAnswered
import com.taskrx.core.sync.ConvexFunctions
import com.taskrx.mobile.auth.AuthGateway
import com.taskrx.mobile.auth.AuthStatus
import com.taskrx.mobile.data.RemindersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
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
 * Asks the server, as a dose's alarm fires, whether that moment was already answered on another
 * device (ADR 0016). Unbounded by itself: [FireGate] bounds it and posts the dose when it is slow.
 *
 * It reads the one instant through `reminderInstances:list` (`since` = `until`, on the
 * `by_user_time` index) and keeps the rows of this reminder, found by its server id. The times go
 * as doubles: the client sends a Kotlin Long as an Int64, which a `v.number()` argument refuses,
 * and a refusal here would look exactly like "not answered".
 */
class FireTimeCheck(
    private val auth: AuthGateway,
    private val ready: StateFlow<Boolean>,
    private val gateway: ConvexGateway,
    private val reminders: RemindersRepository,
) {
    suspend fun check(reminderId: String, scheduledTime: Long): FireCheck {
        val status = auth.status.first { it !is AuthStatus.Loading }
        if (status !is AuthStatus.SignedIn) return FireCheck.SignedOut
        val serverId = reminders.getConvexId(reminderId) ?: return FireCheck.NotOnServer
        ready.first { it }
        val at = scheduledTime.toDouble()
        val rows =
            ServerDocs.instances(gateway.query(ConvexFunctions.INSTANCES_LIST, mapOf("since" to at, "until" to at)))
                .filter { it.reminderId == serverId && it.scheduledTime.toLong() == scheduledTime }
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
 * The check runs in a scope of its own and the wait is what times out, not the work: building the
 * app graph and loading the Convex library block their thread and ignore cancellation, so a
 * timeout placed around them would wait for them anyway.
 */
object FireGate {
    /** Long enough for a cold start to sign in and read one instant; short next to a dose. */
    const val TIMEOUT_MS = 4_000L

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun decide(timeoutMs: Long = TIMEOUT_MS, check: suspend () -> FireCheck): FireCheck {
        val answer =
            work.async {
                try {
                    check()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // An Error too: a native library that will not load must not cost a dose.
                    Log.w(TAG, "fire-time check failed", t)
                    FireCheck.Failed
                }
            }
        val outcome = withTimeoutOrNull(timeoutMs) { answer.await() }
        if (outcome == null) answer.cancel()
        return outcome ?: FireCheck.TimedOut
    }

    private const val TAG = "alarm"
}
