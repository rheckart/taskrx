package com.taskrx.mobile.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.mobile.TaskRxApplication
import com.taskrx.mobile.sync.FireCheck
import com.taskrx.mobile.sync.FireGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Posts the notification for a medication alarm that has just fired, unless the server says the
 * moment was already answered on another device (ADR 0016). The check is bounded by [FireGate]
 * and fails open, so the dose posts whenever there is no positive answer in time.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        handle(context, intent).invokeOnCompletion { pending.finish() }
    }

    /**
     * The receiver's work, returned as a Job so a test can wait on it. [check] is the server's
     * answer; the app graph is built inside it, off the main thread, where a slow or failing
     * build is the gate's to bound.
     */
    internal fun handle(
        context: Context,
        intent: Intent,
        timeoutMs: Long = FireGate.TIMEOUT_MS,
        check: suspend (NotificationSpec) -> FireCheck = { spec -> serverCheck(context, spec) },
    ): Job =
        scope.launch {
            val spec = SpecCodec.decode(intent.getStringExtra(EXTRA_SPEC))
            if (spec == null) {
                Log.w(TAG, "fired with no readable payload; nothing to post")
                return@launch
            }
            // "You can take another dose" is no Occurrence: nothing on the server can answer it.
            val outcome = if (spec.asNeeded) null else FireGate.decide(timeoutMs) { check(spec) }
            if (outcome == FireCheck.Answered) {
                Log.i(TAG, "${spec.id}: answered on another device; not posting")
                return@launch
            }
            if (outcome != null) Log.i(TAG, "${spec.id}: fire-time check $outcome; posting")
            NotificationPoster.post(context, spec)
        }

    private suspend fun serverCheck(context: Context, spec: NotificationSpec): FireCheck {
        val app = context.applicationContext as? TaskRxApplication ?: return FireCheck.LocalOnly
        val check = app.container.fireTimeCheck ?: return FireCheck.LocalOnly
        return check.check(spec.reminderId, spec.scheduledTime)
    }

    companion object {
        const val ACTION_FIRE = "com.taskrx.mobile.action.ALARM_FIRE"
        const val EXTRA_SPEC = "spec"
        private const val TAG = "alarm"

        // Default, not Main: decoding and posting are quick, and the main thread stays free.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
