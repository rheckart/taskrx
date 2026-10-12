package com.taskrx.mobile.notifications

import android.app.AlarmManager
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.mobile.TaskRxApplication
import com.taskrx.mobile.USER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * End to end through the real application container: a Reminder in Room, a broadcast to
 * the receiver, an alarm in AlarmManager.
 */
@RunWith(RobolectricTestRunner::class)
class RearmReceiverTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val container = (app as TaskRxApplication).container
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun seed() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setTaskExecutor(SynchronousExecutor()).build(),
        )
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        container.notifications.currentUser.set(USER)
        runBlocking {
            container.reminders.create(
                com.taskrx.mobile.data.CreateReminderInput(
                    id = "med", userId = USER, kind = ReminderKind.Medication, name = "Metformin",
                    schedule = onceTimes(System.currentTimeMillis() + 3_600_000),
                ),
            )
        }
    }

    private fun broadcast(action: String) = runBlocking { RearmReceiver().handle(app, action)?.join() }

    @Test
    fun `a boot arms the doses from the store with nothing else running`() {
        seed()
        assertTrue(alarms.scheduledAlarms.isEmpty())

        broadcast(Intent.ACTION_BOOT_COMPLETED)

        assertEquals(1, alarms.scheduledAlarms.size)
        assertNotNull(alarms.scheduledAlarms.single().alarmClockInfo)
    }

    @Test
    fun `an app update arms them too`() {
        seed()
        broadcast(Intent.ACTION_MY_PACKAGE_REPLACED)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `a time change arms them too`() {
        seed()
        broadcast(Intent.ACTION_TIME_CHANGED)
        assertEquals(1, alarms.scheduledAlarms.size)
    }

    @Test
    fun `a broadcast it does not know is ignored`() {
        seed()
        assertNull(RearmReceiver().handle(app, Intent.ACTION_BATTERY_LOW))
        assertNull(RearmReceiver().handle(app, null))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `with nobody signed in there is nothing to arm`() {
        seed()
        container.notifications.currentUser.set(null)
        assertNull(RearmReceiver().handle(app, Intent.ACTION_BOOT_COMPLETED))
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test
    fun `onReceive completes without a pending result`() {
        seed()
        RearmReceiver().onReceive(app, Intent(Intent.ACTION_BATTERY_LOW))
    }

    @Test
    fun `a foreground arms them and asks for the permission on the signed-in path only`() {
        seed()
        container.notifications.currentUser.set(null)
        assertNull(container.notifications.onForeground())
        assertNull(container.notifications.permissions.state.value)

        container.notifications.currentUser.set(USER)
        runBlocking { container.notifications.onForeground()?.join() }
        assertEquals(1, alarms.scheduledAlarms.size)
        assertNotNull(container.notifications.permissions.state.value)
    }
}
