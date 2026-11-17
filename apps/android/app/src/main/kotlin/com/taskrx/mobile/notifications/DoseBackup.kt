package com.taskrx.mobile.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.mobile.MainActivity

/**
 * The alarm a dose leaves behind while the fire-time check runs (ADR 0016). The check builds the
 * app graph and asks the server; if the process dies on the way (a native crash, a low-memory
 * kill), nothing else would post the dose. So the receiver arms this first, holding the same spec,
 * [DELAY_MS] out, and cancels it once the dose has posted or the server has said it was answered.
 * When it fires, the receiver posts without asking again.
 *
 * Same primitive as the dose itself: `setAlarmClock`, or the inexact alarm when exact alarms are
 * denied. It is not in the armed ledger: the reschedule diff neither sees nor cancels it.
 */
class DoseBackup(private val context: Context, private val api: AlarmApi) {
    constructor(context: Context) : this(context, SystemAlarmApi(context.getSystemService(AlarmManager::class.java)))

    fun arm(spec: NotificationSpec, now: Long) {
        val operation = requireNotNull(operationFor(spec.id, SpecCodec.encode(spec), create = true))
        val at = now + DELAY_MS
        if (api.canScheduleExactAlarms()) {
            try {
                api.setAlarmClock(at, operation, showIntent())
                return
            } catch (e: SecurityException) {
                Log.w("alarm", "exact alarm refused for the backup of ${spec.id}; degrading", e)
            }
        }
        api.setInexact(at, operation)
    }

    fun cancel(spec: NotificationSpec) {
        operationFor(spec.id, payload = null, create = false)?.let {
            api.cancel(it)
            it.cancel()
        }
    }

    /** Told apart from the dose's own alarm by its action and data URI, so neither replaces the other. */
    private fun operationFor(id: String, payload: String?, create: Boolean): PendingIntent? {
        val intent =
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_BACKUP)
                .setData(Uri.parse("taskrx://alarm-backup/${Uri.encode(id)}"))
        if (payload != null) intent.putExtra(AlarmReceiver.EXTRA_SPEC, payload)
        val flags =
            PendingIntent.FLAG_IMMUTABLE or
                if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun showIntent(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    companion object {
        /** Well past [com.taskrx.mobile.sync.FireGate.TIMEOUT_MS]: a dose a little late, not one posted twice. */
        const val DELAY_MS = 15_000L
    }
}
