package com.taskrx.mobile.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.taskrx.core.notifications.ArmedNotification
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.mobile.MainActivity

/** The AlarmManager calls the gateway makes, narrow enough to substitute in a test. */
interface AlarmApi {
    /** True before API 31, where the permission does not exist. */
    fun canScheduleExactAlarms(): Boolean

    fun setAlarmClock(fireAt: Long, operation: PendingIntent, showIntent: PendingIntent)

    fun setInexact(fireAt: Long, operation: PendingIntent)

    fun cancel(operation: PendingIntent)
}

class SystemAlarmApi(private val manager: AlarmManager) : AlarmApi {
    override fun canScheduleExactAlarms(): Boolean = manager.canScheduleExactAlarms()

    override fun setAlarmClock(fireAt: Long, operation: PendingIntent, showIntent: PendingIntent) =
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showIntent), operation)

    override fun setInexact(fireAt: Long, operation: PendingIntent) =
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, operation)

    override fun cancel(operation: PendingIntent) = manager.cancel(operation)
}

/**
 * The medication Tier: `AlarmManager.setAlarmClock`, the strongest guarantee Android
 * offers - exact, wakes, bypasses Doze, and honoured by the OEM kill policies that
 * ignore every other alarm primitive. It renders a status-bar alarm glyph while any is
 * pending, which for a dose is a feature.
 *
 * Degradation: if exact alarms are denied, or the call throws a [SecurityException]
 * because the permission was revoked between the check and the call, the alarm is armed
 * with `setAndAllowWhileIdle` instead. Timing may drift by minutes; delivery survives.
 * Never a crash, never a dropped dose. The degradation is recorded so that granting the
 * permission later upgrades the alarm in place.
 *
 * AlarmManager cannot be queried, so [armed] is the ledger checked against the system's
 * own PendingIntents: an entry the system no longer holds (lost to a force-stop, say)
 * is dropped rather than believed.
 */
class AlarmClockGateway(
    private val context: Context,
    private val api: AlarmApi,
    private val ledger: ArmedLedger,
) : TierGateway {
    override suspend fun arm(spec: NotificationSpec) {
        val operation = requireNotNull(operationFor(spec.id, SpecCodec.encode(spec), create = true))
        var degraded = !api.canScheduleExactAlarms()
        if (!degraded) {
            try {
                api.setAlarmClock(spec.fireAt, operation, showIntent())
            } catch (e: SecurityException) {
                Log.w("alarm", "exact alarm refused for ${spec.id}; degrading", e)
                degraded = true
            }
        }
        if (degraded) api.setInexact(spec.fireAt, operation)
        ledger.put(spec.id, degraded)
    }

    override suspend fun cancel(id: String) {
        operationFor(id, payload = null, create = false)?.let {
            api.cancel(it)
            it.cancel()
        }
        ledger.remove(id)
    }

    override suspend fun armed(): List<ArmedNotification> =
        ledger.all().filter { entry ->
            val held = operationFor(entry.id, payload = null, create = false) != null
            if (!held) ledger.remove(entry.id)
            held
        }

    /**
     * One PendingIntent per notification id, told apart by the data URI (extras do not
     * take part in intent matching). A fired alarm's intent lingers until the next pass,
     * which cancels it as no longer desired.
     */
    private fun operationFor(id: String, payload: String?, create: Boolean): PendingIntent? {
        val intent =
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData(Uri.parse("taskrx://alarm/${Uri.encode(id)}"))
        if (payload != null) intent.putExtra(AlarmReceiver.EXTRA_SPEC, payload)
        val flags =
            PendingIntent.FLAG_IMMUTABLE or
                if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /** What tapping the status-bar alarm glyph opens. */
    private fun showIntent(): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
