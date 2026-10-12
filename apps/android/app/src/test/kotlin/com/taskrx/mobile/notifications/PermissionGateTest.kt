package com.taskrx.mobile.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.notifications.ANDROID_ALARM_DISABLED
import com.taskrx.core.notifications.ANDROID_ALARM_ENABLED
import com.taskrx.core.notifications.NOTIFICATION_AUTH_AUTHORIZED
import com.taskrx.core.notifications.NOTIFICATION_AUTH_DENIED
import com.taskrx.core.notifications.NOTIFICATION_AUTH_NOT_DETERMINED
import com.taskrx.core.notifications.PermissionRemedy
import com.taskrx.core.notifications.PermissionSeverity
import com.taskrx.core.notifications.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

private class FakePlatform : PermissionPlatform {
    var authorization = NOTIFICATION_AUTH_NOT_DETERMINED
    var alarm = ANDROID_ALARM_ENABLED
    var canPrompt = true
    var prompts = 0
    var notificationSettingsOpened = 0
    var alarmSettingsOpened = 0
    var onPrompt: () -> Unit = {}

    override fun notificationAuthorization() = authorization

    override fun androidAlarm() = alarm

    override fun requestNotifications(): Boolean {
        if (!canPrompt) return false
        prompts++
        onPrompt()
        return true
    }

    override fun openNotificationSettings() {
        notificationSettingsOpened++
    }

    override fun openAlarmSettings() {
        alarmSettingsOpened++
    }
}

/*
 * Mirrors archive/expo-rn:apps/mobile/src/notifications/permission-gate.test.ts. On Android the prompt's
 * answer arrives later, through onPromptResult, instead of as the awaited result of the
 * request; the cases are the same.
 */
class PermissionGateTest {
    private val platform = FakePlatform()
    private val gate = PermissionGate(platform)

    @Test
    fun `prompts on a fresh install and reports the answer once it comes back`() {
        platform.authorization = NOTIFICATION_AUTH_NOT_DETERMINED
        assertNull(gate.sync())
        assertEquals(1, platform.prompts)

        platform.authorization = NOTIFICATION_AUTH_AUTHORIZED
        val state = gate.onPromptResult()

        assertEquals(PermissionStatus.Authorized, state.status)
        assertTrue(state.asked)
        assertNull(state.gap)
        assertEquals(state, gate.state.value)
    }

    @Test
    fun `does not prompt a second time once it has asked`() {
        gate.sync()
        val state = gate.onPromptResult()
        assertEquals(PermissionStatus.Undetermined, state.status)
        assertEquals(PermissionRemedy.Request, state.gap?.remedy)

        gate.sync()
        assertEquals(1, platform.prompts)
    }

    @Test
    fun `surfaces a refusal instead of swallowing it`() {
        platform.authorization = NOTIFICATION_AUTH_DENIED
        val state = gate.sync()!!

        assertEquals(PermissionSeverity.Blocking, state.gap?.severity)
        assertEquals(PermissionRemedy.NotificationSettings, state.gap?.remedy)
        assertEquals(0, platform.prompts)
    }

    @Test
    fun `picks up a permission granted from Settings without a reinstall`() {
        platform.authorization = NOTIFICATION_AUTH_DENIED
        gate.sync()
        platform.authorization = NOTIFICATION_AUTH_AUTHORIZED
        val state = gate.sync()!!

        assertEquals(PermissionStatus.Authorized, state.status)
        assertNull(state.gap)
    }

    @Test
    fun `reports a reminder that will merely be late as its own, lesser gap`() {
        platform.authorization = NOTIFICATION_AUTH_AUTHORIZED
        platform.alarm = ANDROID_ALARM_DISABLED
        val state = gate.sync()!!

        assertEquals(PermissionSeverity.Degraded, state.gap?.severity)
        assertEquals(PermissionRemedy.AlarmSettings, state.gap?.remedy)
        assertFalse(state.exactAlarmGranted)
        assertEquals(PermissionStatus.Partial, state.status)
    }

    @Test
    fun `says it is asking BEFORE the dialog goes up, so the foreground that follows cannot raise a second one`() {
        var concurrent: PermissionGateState? = null
        platform.onPrompt = {
            // The host resumes while the dialog is up, which is another foreground.
            concurrent = gate.sync()
        }
        gate.sync()

        assertEquals(1, platform.prompts)
        assertNull(concurrent, "nothing is published while the dialog is up")
    }

    @Test
    fun `leaves a prompt it could not raise unasked, to try again on the next foreground`() {
        platform.canPrompt = false
        val state = gate.sync()!!
        assertFalse(state.asked)
        assertNull(state.gap, "before the prompt it is a question about to be asked, not a gap")

        platform.canPrompt = true
        gate.sync()
        assertEquals(1, platform.prompts)
    }
}

class PermissionRemedyTest {
    private val platform = FakePlatform()
    private val gate = PermissionGate(platform)

    @Test
    fun `re-runs the prompt for a gap the prompt can still fix`() {
        assertNull(gate.resolve(PermissionRemedy.Request))
        assertEquals(1, platform.prompts)

        platform.authorization = NOTIFICATION_AUTH_AUTHORIZED
        assertEquals(PermissionStatus.Authorized, gate.onPromptResult().status)
    }

    @Test
    fun `does not raise a second dialog over the first`() {
        gate.resolve(PermissionRemedy.Request)
        gate.resolve(PermissionRemedy.Request)
        assertEquals(1, platform.prompts)
    }

    @Test
    fun `with no dialog available a Request just re-reads`() {
        platform.canPrompt = false
        val state = gate.resolve(PermissionRemedy.Request)!!
        assertTrue(state.asked)
    }

    @Test
    fun `sends a refusal to the notification settings`() {
        platform.authorization = NOTIFICATION_AUTH_DENIED
        gate.resolve(PermissionRemedy.NotificationSettings)
        assertEquals(1, platform.notificationSettingsOpened)
        assertEquals(0, platform.alarmSettingsOpened)
    }

    @Test
    fun `sends a missing exact alarm to the alarm settings`() {
        platform.alarm = ANDROID_ALARM_DISABLED
        platform.authorization = NOTIFICATION_AUTH_AUTHORIZED
        gate.resolve(PermissionRemedy.AlarmSettings)
        assertEquals(1, platform.alarmSettingsOpened)
        assertEquals(0, platform.notificationSettingsOpened)
    }

    @Test
    fun `re-reads the platform after sending someone to Settings`() {
        platform.authorization = NOTIFICATION_AUTH_DENIED
        platform.onPrompt = {}
        gate.resolve(PermissionRemedy.NotificationSettings)
        platform.authorization = NOTIFICATION_AUTH_AUTHORIZED
        assertNull(gate.resolve(PermissionRemedy.NotificationSettings)!!.gap)
    }
}

/** The Android half: every answer is a platform query, and the manifest is what the permission model says. */
@RunWith(RobolectricTestRunner::class)
class AndroidPermissionPlatformTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val record = PromptRecord(NotificationPrefs(app))
    private val alarmApi = SystemAlarmApi(app.getSystemService(android.app.AlarmManager::class.java))
    private val platform = AndroidPermissionPlatform(app, alarmApi, record)

    @Test
    fun `a fresh install is undetermined, a granted permission is authorized`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NOTIFICATION_AUTH_NOT_DETERMINED, platform.notificationAuthorization())

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(NOTIFICATION_AUTH_AUTHORIZED, platform.notificationAuthorization())
    }

    @Test
    fun `a refusal after the prompt was shown reads as denied`() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        platform.host = PromptHost {}

        assertTrue(platform.requestNotifications())

        assertEquals(NOTIFICATION_AUTH_DENIED, platform.notificationAuthorization())
    }

    @Test
    fun `notifications switched off in Settings read as denied even with the permission`() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(app.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(false)
        assertEquals(NOTIFICATION_AUTH_DENIED, platform.notificationAuthorization())
    }

    @Test
    fun `cannot raise the prompt with no activity to host it`() {
        assertFalse(platform.requestNotifications())
        assertFalse(record.shown(), "a prompt that never went up is not a prompt shown")
    }


    @Test
    fun `the exact alarm setting follows the system`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertEquals(ANDROID_ALARM_ENABLED, platform.androidAlarm())
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertEquals(ANDROID_ALARM_DISABLED, platform.androidAlarm())
    }

    @Test
    fun `opens the per-app notification settings`() {
        platform.openNotificationSettings()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(app.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }


    @Test
    fun `opens the exact alarm special-access screen`() {
        platform.openAlarmSettings()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
    }

}

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `declares the four permissions and never USE_EXACT_ALARM`() {
        val declared =
            app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().toSet()

        for (permission in listOf("POST_NOTIFICATIONS", "SCHEDULE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED", "WAKE_LOCK")) {
            assertTrue("android.permission.$permission" in declared, "missing $permission")
        }
        assertFalse("android.permission.USE_EXACT_ALARM" in declared, "USE_EXACT_ALARM is a Play-review liability")
    }

    @Test
    fun `the rearm receiver listens for every cause`() {
        for (action in listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )) {
            val receivers = app.packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)
            assertTrue(receivers.any { it.activityInfo.name.endsWith("RearmReceiver") }, "nothing re-arms on $action")
        }
    }

    @Test
    fun `the alarm receiver is private to the app`() {
        val info = app.packageManager.getReceiverInfo(
            android.content.ComponentName(app, AlarmReceiver::class.java),
            0,
        )
        assertFalse(info.exported)
        assertNotNull(info)
    }
}
