package com.taskrx.mobile.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskrx.mobile.TaskRxApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A button on "you can take another dose": Take records a dose now, Skip takes the notification
 * away. Skip writes nothing. The prompt was not an Occurrence that went unanswered, so there is
 * no row to mark Skipped, and a Skipped medication Instance would count against Adherence for a
 * dose nobody was due to take. The next prompt comes with the next dose.
 */
class AsNeededActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val job = handle(context, intent)
        if (job == null) pending?.finish() else job.invokeOnCompletion { pending?.finish() }
    }

    internal fun handle(context: Context, intent: Intent): Job? {
        val action = intent.getStringExtra(EXTRA_ACTION)
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID)
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        if (intent.action != ACTION_ANSWER || reminderId == null || notificationId == null || action !in setOf(TAKE, SKIP)) {
            Log.w("ready", "ignored an as-needed intent that could not be read")
            return null
        }
        val container = (context.applicationContext as TaskRxApplication).container
        return CoroutineScope(Dispatchers.Default).launch {
            if (action == TAKE) {
                try {
                    val medication = container.reminders.get(reminderId)
                    // Deleted since the prompt posted: nothing to record.
                    if (medication != null) container.doses.record(medication, container.clock())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Leave the prompt up: a dose that failed to record must not look as though it did.
                    Log.e("ready", "recording the dose of $reminderId failed", e)
                    return@launch
                }
            }
            context.getSystemService(NotificationManager::class.java).cancel(notificationId, 0)
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.taskrx.mobile.action.READY_ANSWER"
        const val EXTRA_ACTION = "action"
        const val EXTRA_REMINDER_ID = "reminderId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        const val TAKE = "take"
        const val SKIP = "skip"
    }
}
