package com.taskrx.mobile.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.mobile.TaskRxApplication
import com.taskrx.mobile.sync.FireCheck
import com.taskrx.mobile.sync.FireGate
import com.taskrx.mobile.sync.MomentRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts the notification for a medication alarm that has just fired, unless the server says the
 * moment was already answered on another device (ADR 0016). The check is bounded by [FireGate],
 * once for all the doses due at an instant, and fails open, so the dose posts whenever there is no positive answer in time, and a
 * [DoseBackup] posts it if the process dies before either.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        handle(context, intent).invokeOnCompletion { pending.finish() }
    }

    /**
     * The receiver's work, returned as a Job so a test can wait on it. [check] is the server's
     * answer; the app graph is built inside it, off the main thread, where a slow or failing
     * build is the gate's to bound. [backup] is armed before the check starts, so a process that
     * dies during it still posts the dose.
     */
    internal fun handle(
        context: Context,
        intent: Intent,
        timeoutMs: Long = FireGate.TIMEOUT_MS,
        backup: DoseBackup = DoseBackup(context),
        now: () -> Long = System::currentTimeMillis,
        gate: FireGate = FireGate.process,
        check: suspend (NotificationSpec, MomentRead) -> FireCheck = { spec, moment -> serverCheck(context, spec, moment) },
    ): Job {
        val spec = SpecCodec.decode(intent.getStringExtra(EXTRA_SPEC))
        if (spec == null) {
            Log.w(TAG, "fired with no readable payload; nothing to post")
            return Job().apply { complete() }
        }
        if (intent.action == ACTION_BACKUP) {
            Log.w(TAG, "${spec.id}: the fire-time check never finished; posting from the backup")
            return scope.launch { NotificationPoster.post(context, spec) }
        }
        // "You can take another dose" is no Occurrence: nothing on the server can answer it.
        if (spec.asNeeded) return scope.launch { NotificationPoster.post(context, spec) }

        // Here, before anything that can kill the process: the binder call is quick, and from now
        // on a dose that is neither posted nor answered is posted by the backup.
        try {
            backup.arm(spec, now())
        } catch (e: Exception) {
            Log.w(TAG, "${spec.id}: could not arm the backup; checking anyway", e)
        }
        return scope.launch {
            val started = SystemClock.elapsedRealtime()
            val outcome = gate.decide(spec.scheduledTime, timeoutMs) { moment -> check(spec, moment) }
            // The time it took, so real doses show whether the bound holds.
            val took = SystemClock.elapsedRealtime() - started
            if (outcome == FireCheck.Answered) {
                Log.i(TAG, "${spec.id}: answered on another device in $took ms; not posting")
            } else {
                Log.i(TAG, "${spec.id}: fire-time check $outcome in $took ms; posting")
                NotificationPoster.post(context, spec)
            }
            // Not in a finally: a post that throws leaves the backup to try again.
            backup.cancel(spec)
        }
    }

    private suspend fun serverCheck(context: Context, spec: NotificationSpec, moment: MomentRead): FireCheck {
        val app = context.applicationContext as? TaskRxApplication ?: return FireCheck.LocalOnly
        val check = app.container.fireTimeCheck ?: return FireCheck.LocalOnly
        return check.check(spec.reminderId, spec.scheduledTime, moment)
    }

    companion object {
        const val ACTION_FIRE = "com.taskrx.mobile.action.ALARM_FIRE"
        const val ACTION_BACKUP = "com.taskrx.mobile.action.ALARM_BACKUP"
        const val EXTRA_SPEC = "spec"
        private const val TAG = "alarm"

        // Default, not Main: decoding and posting are quick, and the main thread stays free.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
