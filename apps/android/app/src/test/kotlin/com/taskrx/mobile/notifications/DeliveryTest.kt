package com.taskrx.mobile.notifications

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private val DOSE = NotificationSpec("med-1000", Tier.Medication, 1000, "Metformin", "Time to take (500mg)", "med", 1000)
private val TASK = NotificationSpec("task-chore-2000", Tier.Task, 2000, "Call the pharmacy", "Time to do it", "chore", 2000)

@RunWith(RobolectricTestRunner::class)
class ChannelsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `creates the two frozen channels with their importances`() {
        Channels.ensure(context)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("medication_reminders").importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("task_reminders").importance)
        assertEquals("Medication Reminders", manager.getNotificationChannel("medication_reminders").name)
        assertEquals("Task Reminders", manager.getNotificationChannel("task_reminders").name)
    }

    @Test
    fun `creating them again keeps what the person set`() {
        Channels.ensure(context)
        Channels.ensure(context)
        assertEquals(2, manager.notificationChannels.size)
    }
}

@RunWith(RobolectricTestRunner::class)
class PostingTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `an alarm firing posts the dose on the medication channel`() {
        AlarmReceiver().onReceive(context, Intent().putExtra(AlarmReceiver.EXTRA_SPEC, SpecCodec.encode(DOSE)))

        val posted = shadowOf(manager).getNotification(DOSE.id, 0)
        assertNotNull(posted)
        assertEquals("medication_reminders", posted.channelId)
        assertEquals("Metformin", posted.extras.getString("android.title"))
        assertEquals("Time to take (500mg)", posted.extras.getString("android.text"))
    }

    @Test
    fun `an unreadable alarm payload posts nothing and does not throw`() {
        AlarmReceiver().onReceive(context, Intent().putExtra(AlarmReceiver.EXTRA_SPEC, "not json"))
        AlarmReceiver().onReceive(context, Intent())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `the worker posts a task on the task channel`() {
        val worker =
            TestListenableWorkerBuilder<TaskNotificationWorker>(context)
                .setInputData(workDataOf(TaskNotificationWorker.KEY_SPEC to SpecCodec.encode(TASK)))
                .build()

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("task_reminders", shadowOf(manager).getNotification(TASK.id, 0).channelId)
    }

    @Test
    fun `the worker fails on an unreadable payload`() {
        val worker = TestListenableWorkerBuilder<TaskNotificationWorker>(context).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a tap opens the answer screen with the reminder and the moment, for either Kind`() {
        for (spec in listOf(DOSE, TASK)) {
            NotificationPoster.post(context, spec)
            val tap = shadowOf(shadowOf(manager).getNotification(spec.id, 0).contentIntent).savedIntent

            assertEquals(TapHandler.ACTION_TAP, tap.action)
            assertEquals(spec.reminderId, tap.getStringExtra("reminderId"))
            assertEquals(spec.scheduledTime.toString(), tap.getStringExtra("scheduledTime"))
        }
    }

    @Test
    fun `a snooze taps through to the Occurrence it was deferred from`() {
        val snooze = DOSE.copy(id = "snooze-x", fireAt = 9000, scheduledTime = 1000, snoozed = true)
        NotificationPoster.post(context, snooze)
        val tap = shadowOf(shadowOf(manager).getNotification("snooze-x", 0).contentIntent).savedIntent
        assertEquals("1000", tap.getStringExtra("scheduledTime"))
    }
}

@RunWith(RobolectricTestRunner::class)
class SpecCodecTest {
    @Test
    fun `round trips both Tiers and the snooze flag`() {
        for (spec in listOf(DOSE, TASK, TASK.copy(snoozed = true))) {
            assertEquals(spec, SpecCodec.decode(SpecCodec.encode(spec)))
        }
    }

    @Test
    fun `decoding is total`() {
        assertNull(SpecCodec.decode(null))
        assertNull(SpecCodec.decode("{"))
        assertNull(SpecCodec.decode("""{"id":"a","tier":"nope","fireAt":1,"title":"t","body":"b","reminderId":"r","scheduledTime":1}"""))
    }
}
