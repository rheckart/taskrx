package com.taskrx.mobile.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.taskrx.core.batch.BatchAction
import com.taskrx.mobile.TaskRxApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * A button on the batch notification: take, snooze or skip every dose in it. Works with the
 * app closed, which is the point; the writes and the shade cleanup are `BatchAnswers`'.
 */
class BatchActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val job = handle(context, intent)
        if (job == null) pending?.finish() else job.invokeOnCompletion { pending?.finish() }
    }

    internal fun handle(context: Context, intent: Intent): Job? {
        val action = BatchAction.fromWire(intent.getStringExtra(EXTRA_ACTION))
        val scheduledTime = intent.getLongExtra(EXTRA_TIME, -1L)
        val reminderIds = intent.getStringArrayListExtra(EXTRA_REMINDER_IDS)
        if (intent.action != ACTION_BATCH || action == null || scheduledTime < 0 || reminderIds.isNullOrEmpty()) {
            Log.w("batch", "ignored a batch intent that could not be read")
            return null
        }
        // The batch button stands for the whole moment, so it answers what is in the shade now, not only what it was built with.
        val ids = if (intent.getBooleanExtra(EXTRA_WHOLE_MOMENT, false)) (reminderIds + NotificationPoster.doseReminderIds(context, scheduledTime)).distinct() else reminderIds
        val container = (context.applicationContext as TaskRxApplication).container
        return CoroutineScope(Dispatchers.Default).launch {
            val result = container.batch.answer(action, scheduledTime, ids)
            Log.i("batch", "${action.wire} $scheduledTime: changed=${result.changed} failed=${result.failed}")
        }
    }

    companion object {
        const val ACTION_BATCH = "com.taskrx.mobile.action.BATCH_ANSWER"
        const val EXTRA_ACTION = "action"
        const val EXTRA_TIME = "scheduledTime"
        /** Set on the batch notification's buttons only; a single dose's buttons answer that dose. */
        const val EXTRA_WHOLE_MOMENT = "wholeMoment"
        const val EXTRA_REMINDER_IDS = "reminderIds"
    }
}
