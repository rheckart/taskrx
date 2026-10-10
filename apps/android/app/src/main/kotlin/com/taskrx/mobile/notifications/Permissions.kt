package com.taskrx.mobile.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.taskrx.core.notifications.PermissionGap
import com.taskrx.core.notifications.PermissionGateInput
import com.taskrx.core.notifications.PermissionRemedy
import com.taskrx.core.notifications.PermissionSnapshot
import com.taskrx.core.notifications.PermissionSnapshotInput
import com.taskrx.core.notifications.PermissionStatus
import com.taskrx.core.notifications.androidAlarmSetting
import com.taskrx.core.notifications.derivePermissionGap
import com.taskrx.core.notifications.derivePermissionStatus
import com.taskrx.core.notifications.notificationAuthorization
import com.taskrx.core.notifications.shouldRequestNotificationPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the gate needs from the platform. Every answer is a fresh read; nothing here caches a verdict. */
interface PermissionPlatform {
    /** One of the `NOTIFICATION_AUTH_*` values. */
    fun notificationAuthorization(): Int

    /** One of the `ANDROID_ALARM_*` values. */
    fun androidAlarm(): Int

    /**
     * Raises the OS prompt and returns true, or returns false when it cannot be raised
     * right now (no activity to host it, or no runtime permission on this API level).
     */
    fun requestNotifications(): Boolean

    fun openNotificationSettings()

    fun openAlarmSettings()
}

/** Hosts the OS permission dialog; the activity registers itself here. */
fun interface PromptHost {
    fun launch()
}

class AndroidPermissionPlatform(
    private val context: Context,
    private val alarms: AlarmApi,
    private val record: PromptRecord,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : PermissionPlatform {
    @Volatile var host: PromptHost? = null

    override fun notificationAuthorization(): Int =
        notificationAuthorization(
            sdkInt = sdk,
            permissionGranted =
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            notificationsEnabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            promptShownBefore = record.shown(),
        )

    override fun androidAlarm(): Int = androidAlarmSetting(sdk, alarms.canScheduleExactAlarms())

    override fun requestNotifications(): Boolean {
        val target = host ?: return false
        if (sdk < Build.VERSION_CODES.TIRAMISU) return false
        record.markShown()
        target.launch()
        return true
    }

    /** The per-app notification settings: once a runtime permission is refused the prompt is spent. */
    override fun openNotificationSettings() {
        val intent =
            if (sdk >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun openAlarmSettings() {
        if (sdk < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

data class PermissionGateState(
    val status: PermissionStatus,
    val exactAlarmGranted: Boolean,
    /** Whether the OS prompt has been put in front of the person during this run. */
    val asked: Boolean,
    val gap: PermissionGap?,
)

/**
 * The one path in the app that asks whether it may notify anybody. Mirrors
 * permission-gate.ts and permission-provider.tsx; the decisions (when to prompt, what to
 * say) are :core's `shouldRequestNotificationPermission` and `derivePermissionGap`.
 *
 * Exposes [state] for the banner; drawing it is the UI step's job.
 *
 *  - Called on the signed-in path, on every foreground: the prompt is raised at most once
 *    per run and every later call is a re-read, which is what notices a permission granted
 *    from Settings without a reinstall.
 *  - `asked` is in memory on purpose. Across runs the platform's answer is the authority;
 *    a stored "we already asked" would be a cached verdict.
 *  - The prompt is marked asked as it goes up, and while it is up [sync] does nothing:
 *    dismissing the dialog resumes the host, which is another foreground, and a gate that
 *    answered it would put the dialog straight back on screen. Android delivers the
 *    result later, through [onPromptResult].
 */
class PermissionGate(private val platform: PermissionPlatform) {
    private val current = MutableStateFlow<PermissionGateState?>(null)
    val state: StateFlow<PermissionGateState?> = current

    private var asked = false
    private var promptInFlight = false

    private fun read(): PermissionSnapshot =
        derivePermissionStatus(PermissionSnapshotInput(platform.notificationAuthorization(), platform.androidAlarm()))

    private fun publish(snapshot: PermissionSnapshot, askedNow: Boolean): PermissionGateState {
        val next =
            PermissionGateState(
                status = snapshot.status,
                exactAlarmGranted = snapshot.exactAlarmGranted,
                asked = askedNow,
                gap = derivePermissionGap(PermissionGateInput(snapshot, askedNow)),
            )
        current.value = next
        return next
    }

    @Synchronized
    fun sync(): PermissionGateState? {
        if (promptInFlight) return current.value
        val snapshot = read()
        if (shouldRequestNotificationPermission(PermissionGateInput(snapshot, asked)) && raisePrompt()) {
            return current.value
        }
        return publish(snapshot, asked)
    }

    /** The prompt came back. Re-reads; the platform's answer is the state. */
    @Synchronized
    fun onPromptResult(): PermissionGateState {
        promptInFlight = false
        asked = true
        return publish(read(), askedNow = true)
    }

    /** Acts on the button attached to a gap, then re-reads what the platform now holds. */
    @Synchronized
    fun resolve(remedy: PermissionRemedy): PermissionGateState? {
        when (remedy) {
            PermissionRemedy.Request -> if (!promptInFlight && raisePrompt()) return current.value
            PermissionRemedy.NotificationSettings -> platform.openNotificationSettings()
            PermissionRemedy.AlarmSettings -> platform.openAlarmSettings()
        }
        asked = true
        return publish(read(), askedNow = true)
    }

    private fun raisePrompt(): Boolean {
        // Recorded as the dialog goes up, not when it comes down.
        asked = true
        promptInFlight = true
        if (platform.requestNotifications()) return true
        asked = false
        promptInFlight = false
        return false
    }
}
