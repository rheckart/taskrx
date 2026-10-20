package com.taskrx.mobile.notifications

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.taskrx.core.notifications.ArmedNotification
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.withFixture
import kotlinx.coroutines.flow.first
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** Wraps a gateway and remembers every call, so a test can say what a pass did and did not do. */
class RecordingGateway(private val inner: TierGateway) : TierGateway {
    val armedIds = mutableListOf<String>()
    val cancelledIds = mutableListOf<String>()

    override suspend fun arm(spec: NotificationSpec) {
        armedIds += spec.id
        inner.arm(spec)
    }

    override suspend fun cancel(id: String) {
        cancelledIds += id
        inner.cancel(id)
    }

    override suspend fun armed(): List<ArmedNotification> = inner.armed()

    fun reset() {
        armedIds.clear()
        cancelledIds.clear()
    }
}

/** An AlarmApi that delegates, and can be told to refuse the exact call the way a revoked permission does. */
class SwitchableAlarmApi(private val inner: AlarmApi) : AlarmApi {
    var refuseExact = false
    var inexactCalls = 0

    override fun canScheduleExactAlarms() = inner.canScheduleExactAlarms()

    override fun setAlarmClock(fireAt: Long, operation: PendingIntent, showIntent: PendingIntent) {
        if (refuseExact) throw SecurityException("exact alarms revoked")
        inner.setAlarmClock(fireAt, operation, showIntent)
    }

    override fun setInexact(fireAt: Long, operation: PendingIntent) {
        inexactCalls++
        inner.setInexact(fireAt, operation)
    }

    override fun cancel(operation: PendingIntent) = inner.cancel(operation)
}

/**
 * The real gateways over Robolectric's AlarmManager and a real (test-initialised)
 * WorkManager, with real Room underneath. Only the recording wrappers are fakes.
 */
class NotifFixture(val fx: Fixture) {
    val context: Application = ApplicationProvider.getApplicationContext()
    val alarmManager: AlarmManager = context.getSystemService(AlarmManager::class.java)
    val alarmShadow: ShadowAlarmManager = shadowOf(alarmManager)
    val api = SwitchableAlarmApi(SystemAlarmApi(alarmManager))
    val prefs = NotificationPrefs(context)
    val ledger = ArmedLedger(prefs)
    val snoozes = SnoozeStore(prefs)

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    val workManager: WorkManager = WorkManager.getInstance(context)
    val medication = RecordingGateway(AlarmClockGateway(context, api, ledger))
    val tasks = RecordingGateway(WorkRequestGateway(workManager) { fx.now })

    val scheduler =
        NotificationScheduler(
            reminders = fx.container.reminders,
            instances = fx.container.instances,
            medication = medication,
            tasks = tasks,
            snoozes = snoozes,
            exactAllowed = api::canScheduleExactAlarms,
            clock = { fx.now },
            zone = { java.time.ZoneOffset.UTC },
        )

    fun resetRecordings() {
        medication.reset()
        tasks.reset()
    }

    /** Ids of everything AlarmManager holds, read from the PendingIntents the system was given. */
    fun alarmIds(): List<String> = alarmShadow.scheduledAlarms.map { shadowOf(it.operation!!).savedIntent.data!!.lastPathSegment!! }

    fun alarm(id: String): ShadowAlarmManager.ScheduledAlarm =
        alarmShadow.scheduledAlarms.single { shadowOf(it.operation!!).savedIntent.data!!.lastPathSegment == id }

    /** Ids of every work request not yet finished. */
    suspend fun workIds(): List<String> =
        workManager.getWorkInfosByTagFlow(WorkRequestGateway.TAG).first()
            .filter { it.state == WorkInfo.State.ENQUEUED }
            .flatMap { it.tags }
            .filter { it.startsWith(WorkRequestGateway.ID_TAG_PREFIX) }
            .map { it.removePrefix(WorkRequestGateway.ID_TAG_PREFIX) }

    /** The system losing every alarm, as a reboot does (work requests persist). */
    fun loseAllAlarms() {
        for (alarm in alarmShadow.scheduledAlarms.toList()) alarmManager.cancel(alarm.operation!!)
        for (id in ledger.all().map { it.id }) {
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_FIRE).setData(Uri.parse("taskrx://alarm/${Uri.encode(id)}")),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            )
            pi?.cancel()
        }
    }
}

fun withNotifs(now: Long, block: suspend NotifFixture.() -> Unit) = withFixture(now) { NotifFixture(this).block() }

val Tier.channel get() = com.taskrx.core.notifications.deliveryFor(this).channelId
