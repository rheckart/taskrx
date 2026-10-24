package com.taskrx.mobile.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.batch.MIN_BATCH_SIZE
import com.taskrx.core.batch.batchNotificationId
import com.taskrx.core.batch.batchText
import com.taskrx.core.notifications.ChannelImportance
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.core.notifications.deliveryFor
import com.taskrx.mobile.MainActivity

/**
 * Posts a notification when its alarm or work request fires. No database read: the spec
 * rode in with the trigger, so a dose posts even if storage cannot be opened.
 */
object NotificationPoster {
    const val TAG = "notify"

    fun post(context: Context, spec: NotificationSpec) {
        Channels.ensure(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) {
            // The framework would discard this without an error. Say so, because the
            // alternative is a reminder that fired on time and left no trace.
            Log.w(TAG, "notifications are off; ${spec.id} will be discarded")
        }

        val delivery = deliveryFor(spec.tier)
        val builder =
            NotificationCompat.Builder(context, delivery.channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(spec.title)
                .setContentText(spec.body)
                .setPriority(
                    if (delivery.importance == ChannelImportance.High) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT,
                )
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(tapIntent(context, spec))
                // Read back off the shade to find the other doses due at this moment: no database read.
                .addExtras(
                    Bundle().apply {
                        if (spec.tier == Tier.Medication) putBoolean(EXTRA_DOSE, true)
                        putString(EXTRA_REMINDER, spec.reminderId)
                        putLong(EXTRA_TIME, spec.scheduledTime)
                        putString(EXTRA_NAME, spec.title)
                    },
                )
        // Either Kind can be answered from the shade. A dose can also be snoozed and joins a batch;
        // a task is finished or skipped, and never has a batch (it is not a dose).
        val actions = if (spec.tier == Tier.Medication) DOSE_ACTIONS else TASK_ACTIONS
        val scope = if (spec.tier == Tier.Medication) "dose" else "task"
        for ((action, label) in actions) {
            builder.addAction(0, label, actionIntent(context, action, spec.scheduledTime, arrayListOf(spec.reminderId), "$scope/${Uri.encode(spec.id)}"))
        }
        manager.notify(spec.id, 0, builder.build())
        if (spec.tier == Tier.Medication) showBatch(context, manager, spec.scheduledTime, doses(manager, spec.scheduledTime))
    }

    private fun doses(manager: NotificationManager, scheduledTime: Long): List<StatusBarNotification> =
        manager.activeNotifications.filter {
            val extras = it.notification.extras
            extras.getBoolean(EXTRA_DOSE) && extras.getLong(EXTRA_TIME) == scheduledTime
        }

    /**
     * Two or more doses in the shade for one moment get a third notification that answers
     * them all. Rebuilt from what is showing on every post, so alarms that fire in the
     * same second converge: the last one to post sees every dose and writes the full list.
     * It never alerts: each dose already did.
     */
    private fun showBatch(context: Context, manager: NotificationManager, scheduledTime: Long, showing: List<StatusBarNotification>) {
        if (showing.size < MIN_BATCH_SIZE) {
            manager.cancel(batchNotificationId(scheduledTime), 0)
            return
        }
        val reminderIds = ArrayList(showing.mapNotNull { it.notification.extras.getString(EXTRA_REMINDER) }.distinct())
        val names = showing.mapNotNull { it.notification.extras.getString(EXTRA_NAME) }
        val text = batchText(names)
        val builder =
            NotificationCompat.Builder(context, deliveryFor(Tier.Medication).channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(text.title)
                .setContentText(text.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, MainActivity::class.java)
                            .setData(Uri.parse("taskrx://batch-open/$scheduledTime"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
        for ((action, label) in ACTION_LABELS) {
            builder.addAction(0, label, actionIntent(context, action, scheduledTime, reminderIds, "batch"))
        }
        manager.notify(batchNotificationId(scheduledTime), 0, builder.build())
    }

    /**
     * One PendingIntent per (scope, moment, action), where the scope is the batch or one
     * dose. A PendingIntent is identified by its action and data, never its extras, so
     * without a data URI of its own a second batch's (or dose's) buttons would overwrite
     * the first's and answer the wrong doses.
     */
    private fun actionIntent(context: Context, action: BatchAction, scheduledTime: Long, reminderIds: ArrayList<String>, scope: String): PendingIntent {
        val intent =
            Intent(context, BatchActionReceiver::class.java)
                .setAction(BatchActionReceiver.ACTION_BATCH)
                .setData(Uri.parse("taskrx://$scope/$scheduledTime/${action.wire}"))
                .putExtra(BatchActionReceiver.EXTRA_ACTION, action.wire)
                .putExtra(BatchActionReceiver.EXTRA_TIME, scheduledTime)
                .putStringArrayListExtra(BatchActionReceiver.EXTRA_REMINDER_IDS, reminderIds)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Removes the shade entries of [reminderIds] at [scheduledTime], doses and tasks alike. The
     * moment's batch notification is rebuilt from the doses that remain, or removed when fewer
     * than a batch are left: answering one dose of three leaves a batch of two, not of three.
     */
    fun dismiss(context: Context, scheduledTime: Long, reminderIds: List<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun StatusBarNotification.answered() = notification.extras.getString(EXTRA_REMINDER) in reminderIds
        // A task's notification carries the same extras but is not a dose, so it is found here and not in `doses`.
        for (sbn in manager.activeNotifications) {
            if (sbn.notification.extras.getLong(EXTRA_TIME) == scheduledTime && sbn.answered()) manager.cancel(sbn.tag, sbn.id)
        }
        showBatch(context, manager, scheduledTime, doses(manager, scheduledTime).filterNot { it.answered() })
    }

    private val DOSE_ACTIONS = listOf(BatchAction.Take to "Take", BatchAction.Snooze to "Snooze", BatchAction.Skip to "Skip")
    private val TASK_ACTIONS = listOf(BatchAction.Take to "Done", BatchAction.Skip to "Skip")
    private val ACTION_LABELS = listOf(BatchAction.Take to "Take all", BatchAction.Snooze to "Snooze all", BatchAction.Skip to "Skip all")

    private const val EXTRA_DOSE = "taskrx.dose"
    private const val EXTRA_REMINDER = "taskrx.reminderId"
    private const val EXTRA_TIME = "taskrx.scheduledTime"
    private const val EXTRA_NAME = "taskrx.name"

    /**
     * Opens the app on the answer screen, Kind-neutral: the same intent for a dose and a
     * task, carrying `reminderId` and `scheduledTime` as strings exactly as the Expo
     * payload did.
     */
    private fun tapIntent(context: Context, spec: NotificationSpec): PendingIntent {
        val intent =
            Intent(context, MainActivity::class.java)
                .setAction(TapHandler.ACTION_TAP)
                .setData(Uri.parse("taskrx://tap/${Uri.encode(spec.id)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(TapHandler.EXTRA_REMINDER_ID, spec.reminderId)
                .putExtra(TapHandler.EXTRA_SCHEDULED_TIME, spec.scheduledTime.toString())
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
