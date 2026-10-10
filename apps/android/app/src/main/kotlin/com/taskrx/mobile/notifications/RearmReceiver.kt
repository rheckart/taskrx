package com.taskrx.mobile.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskrx.core.notifications.rearmCauseOf
import com.taskrx.mobile.TaskRxApplication
import kotlinx.coroutines.Job

/**
 * Re-arms after the system has made the armed state stale: reboot and app update (both
 * clear every alarm), a clock or zone change (shifts both the occurrences and a work
 * request's relative delay), and a change to the exact-alarm permission (so a degraded
 * dose is upgraded, or a revoked one handled).
 *
 * Which Tiers are re-created is :core's `tiersToRearm`.
 */
class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val job = handle(context, intent.action)
        if (job == null) pending?.finish() else job.invokeOnCompletion { pending?.finish() }
    }

    internal fun handle(context: Context, action: String?): Job? {
        val cause = rearmCauseOf(action)
        if (cause == null) {
            Log.i("rearm", "ignored action=$action")
            return null
        }
        Log.i("rearm", "re-arming: $cause")
        val container = (context.applicationContext as TaskRxApplication).container
        return container.notifications.rearm(cause)
    }
}
