package com.taskrx.mobile.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Posts the notification for a medication alarm that has just fired. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val spec = SpecCodec.decode(intent.getStringExtra(EXTRA_SPEC))
        if (spec == null) {
            Log.w("alarm", "fired with no readable payload; nothing to post")
            return
        }
        NotificationPoster.post(context, spec)
    }

    companion object {
        const val ACTION_FIRE = "com.taskrx.mobile.action.ALARM_FIRE"
        const val EXTRA_SPEC = "spec"
    }
}
