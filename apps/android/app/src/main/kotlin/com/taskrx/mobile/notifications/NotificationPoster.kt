package com.taskrx.mobile.notifications

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.taskrx.core.notifications.ChannelImportance
import com.taskrx.core.notifications.NotificationSpec
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
        val notification =
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
                .build()
        manager.notify(spec.id, 0, notification)
    }

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
