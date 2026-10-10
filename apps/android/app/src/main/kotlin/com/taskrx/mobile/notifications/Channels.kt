package com.taskrx.mobile.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.taskrx.core.notifications.ChannelImportance
import com.taskrx.core.notifications.Tier
import com.taskrx.core.notifications.deliveryFor

/**
 * Creates one channel per Tier. The medication channel is HIGH so a dose bypasses Do Not
 * Disturb and alerts audibly; the task channel is DEFAULT so "walk the dog" sounds and
 * sits in the shade without taking over the screen. Separate channels also let the
 * person silence their tasks without touching their doses.
 *
 * Creating an existing channel is a no-op, which is what keeps the person's overrides.
 * Must run before the first post.
 */
object Channels {
    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        for (tier in Tier.entries) {
            val delivery = deliveryFor(tier)
            val importance =
                when (delivery.importance) {
                    ChannelImportance.High -> NotificationManager.IMPORTANCE_HIGH
                    ChannelImportance.Default -> NotificationManager.IMPORTANCE_DEFAULT
                }
            val channel =
                NotificationChannel(delivery.channelId, delivery.channelName, importance).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(300, 500)
                }
            manager.createNotificationChannel(channel)
        }
    }
}
