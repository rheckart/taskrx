package com.taskrx.mobile.notifications

import android.app.AlarmManager
import android.content.Context
import androidx.work.WorkManager
import com.taskrx.core.notifications.RearmCause
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.RemindersRepository
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the notification layer. The platform edges are parameters so a test can swap
 * them: [alarmApi] for AlarmManager, [workManager] for WorkManager.
 */
class NotificationsModule(
    private val context: Context,
    reminders: RemindersRepository,
    clock: Clock,
    alarmApi: AlarmApi = SystemAlarmApi(context.getSystemService(AlarmManager::class.java)),
    workManager: () -> WorkManager = { WorkManager.getInstance(context) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = NotificationPrefs(context)
    val currentUser = CurrentUser(prefs)
    val snoozes = SnoozeStore(prefs)
    val taps = TapHandler()

    val platform = AndroidPermissionPlatform(context, alarmApi, PromptRecord(prefs))
    val permissions = PermissionGate(platform)

    /** The two Tiers' subsystems. Public for the debug device harness, which reads what each holds. */
    val medicationGateway: TierGateway = AlarmClockGateway(context, alarmApi, ArmedLedger(prefs))
    val taskGateway: TierGateway = LazyTaskGateway { WorkRequestGateway(workManager(), clock) }

    val scheduler =
        NotificationScheduler(
            reminders = reminders,
            medication = medicationGateway,
            tasks = taskGateway,
            snoozes = snoozes,
            exactAllowed = alarmApi::canScheduleExactAlarms,
            clock = clock,
        )

    fun createChannels() = Channels.ensure(context)

    /**
     * The app came to the foreground. Permission first, and only on the signed-in path
     * (a fresh install's first screen is sign-in; a permission dialog there lands before
     * the person has told the app who they are). Then the reschedule, which does not wait
     * on the answer: alarms are armed either way, and a banner says when they cannot arrive.
     */
    fun onForeground(): Job? {
        val userId = currentUser.get() ?: return null
        permissions.sync()
        return scope.launch { logged("reschedule") { scheduler.rescheduleAll(userId) } }
    }

    /**
     * Logged and dropped, like the Expo layout's `rescheduleAll().catch`: a failed pass
     * must not take the process down (a boot receiver would crash-loop), and the next
     * foreground runs it again.
     */
    private suspend fun logged(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("notifications", "$what failed", e)
        }
    }

    /**
     * The reschedule every write is followed by, so what is armed never lags what is
     * stored. Logged and dropped for the reason [onForeground] documents: the write it
     * follows has already happened, and failing it over a notification would lose nothing
     * the next foreground does not repair.
     */
    suspend fun rescheduleAfterWrite(userId: String) = logged("reschedule after write") { scheduler.rescheduleAll(userId) }

    /** A system broadcast says the armed state may be stale. */
    fun rearm(cause: RearmCause): Job? {
        val userId = currentUser.get() ?: return null
        return scope.launch { logged("rearm $cause") { scheduler.rescheduleAll(userId, cause) } }
    }
}

/** Defers building the WorkManager-backed gateway to first use, so construction never initializes WorkManager. */
private class LazyTaskGateway(build: () -> TierGateway) : TierGateway {
    private val delegate by lazy(build)

    override suspend fun arm(spec: com.taskrx.core.notifications.NotificationSpec) = delegate.arm(spec)

    override suspend fun cancel(id: String) = delegate.cancel(id)

    override suspend fun armed() = delegate.armed()
}
