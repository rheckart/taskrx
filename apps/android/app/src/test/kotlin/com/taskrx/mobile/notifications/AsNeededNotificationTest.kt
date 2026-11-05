package com.taskrx.mobile.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.asNeededTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.mobile.TaskRxApplication
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.InstanceFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val READY_AT = 15 * 3_600_000L

/**
 * "You can take another dose": what posts when the wait is over and what its buttons do, through
 * the real container, a real broadcast receiver and Robolectric's notification manager.
 */
@RunWith(RobolectricTestRunner::class)
class AsNeededNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val container = (app as TaskRxApplication).container
    private val manager = app.getSystemService(NotificationManager::class.java)

    private val ready =
        NotificationSpec(
            id = "ready-tylenol-$READY_AT",
            tier = Tier.Medication,
            fireAt = READY_AT,
            title = "Tylenol",
            body = "You can take another dose",
            reminderId = "tylenol",
            scheduledTime = READY_AT,
            asNeeded = true,
        )

    private fun create() {
        container.notifications.currentUser.set(USER)
        runBlocking {
            container.reminders.create(
                CreateReminderInput(id = "tylenol", userId = USER, kind = ReminderKind.Medication, name = "Tylenol", schedule = asNeededTimes(240)),
            )
        }
    }

    private fun press(label: String) {
        val button = manager.activeNotifications.single().notification.actions.single { it.title == label }
        val intent = shadowOf(button.actionIntent).savedIntent
        AsNeededActionReceiver().handle(app, intent)?.let { job -> runBlocking { job.join() } }
    }

    private fun taken() = runBlocking { container.instances.list(InstanceFilter(USER)) }

    @Test
    fun `it offers Take and Skip, and no Snooze`() {
        NotificationPoster.post(app, ready)

        assertEquals(listOf("Take", "Skip"), manager.activeNotifications.single().notification.actions.map { it.title.toString() })
    }

    @Test
    fun `a tap opens the app on Today, not an answer screen with nothing to answer`() {
        NotificationPoster.post(app, ready)

        val tap = shadowOf(manager.activeNotifications.single().notification.contentIntent).savedIntent
        assertNull(tap.getStringExtra(TapHandler.EXTRA_REMINDER_ID))
        assertNull(tap.action)
    }

    @Test
    fun `it joins no batch, even with a dose due at the same moment`() {
        val dose = NotificationSpec("a-$READY_AT", Tier.Medication, READY_AT, "Name a", "Time to take", "a", READY_AT)
        val other = dose.copy(id = "b-$READY_AT", reminderId = "b", title = "Name b")

        NotificationPoster.post(app, dose)
        NotificationPoster.post(app, ready)

        // Two notifications, and no "2 doses due": the prompt is not a dose that was due.
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.none { it.tag.startsWith("batch-") })
        NotificationPoster.post(app, other)
        assertEquals(1, manager.activeNotifications.count { it.tag.startsWith("batch-") })
        assertEquals("2 doses due", manager.activeNotifications.single { it.tag.startsWith("batch-") }.notification.extras.getString("android.title"))
    }

    @Test
    fun `Take records a dose now and clears the prompt`() {
        create()
        NotificationPoster.post(app, ready)

        press("Take")

        val rows = taken()
        assertEquals(listOf(InstanceStatus.Completed), rows.map { it.status })
        // The application's clock is the real one here, so "now" is a minute's width, not an instant.
        assertTrue(kotlin.math.abs(rows.single().completedAt!! - System.currentTimeMillis()) < 60_000)
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `Skip clears the prompt and writes nothing`() {
        create()
        NotificationPoster.post(app, ready)

        press("Skip")

        assertEquals(emptyList(), taken())
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test
    fun `Take for a medication deleted since the prompt posted records nothing`() {
        container.notifications.currentUser.set(USER)
        NotificationPoster.post(app, ready)

        press("Take")

        assertEquals(emptyList(), taken())
    }

    @Test
    fun `an intent that cannot be read is ignored`() {
        assertNull(AsNeededActionReceiver().handle(app, Intent("something.else")))
        assertNull(
            AsNeededActionReceiver().handle(
                app,
                Intent(AsNeededActionReceiver.ACTION_ANSWER).putExtra(AsNeededActionReceiver.EXTRA_ACTION, "explode"),
            ),
        )
    }
}
