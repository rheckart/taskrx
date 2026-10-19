package com.taskrx.mobile.notifications

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.batch.BatchAction
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.mobile.TaskRxApplication
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.core.domain.onceTimes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val NINE = 9 * 3_600_000L

/**
 * The shade: what posts when doses fire together, and what its buttons do. Through the
 * real application container, a real broadcast receiver and Robolectric's notification
 * manager.
 */
@RunWith(RobolectricTestRunner::class)
class BatchNotificationTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val container = (app as TaskRxApplication).container
    private val manager = app.getSystemService(NotificationManager::class.java)

    private fun spec(id: String, at: Long = NINE, tier: Tier = Tier.Medication) =
        NotificationSpec(
            id = if (tier == Tier.Medication) "$id-$at" else "task-$id-$at",
            tier = tier,
            fireAt = at,
            title = "Name $id",
            body = "Time to take",
            reminderId = id,
            scheduledTime = at,
        )

    private fun active(): List<StatusBarNotification> = manager.activeNotifications.toList()

    private fun batchOf(at: Long = NINE) = active().singleOrNull { it.tag == "batch-$at" }

    private fun create(vararg ids: String) {
        container.notifications.currentUser.set(USER)
        runBlocking {
            for (id in ids) {
                container.reminders.create(
                    CreateReminderInput(id = id, userId = USER, kind = ReminderKind.Medication, name = "Name $id", schedule = onceTimes(NINE)),
                )
            }
        }
    }

    private fun press(batch: StatusBarNotification, action: BatchAction) {
        val label = mapOf(BatchAction.Take to "Take all", BatchAction.Snooze to "Snooze all", BatchAction.Skip to "Skip all").getValue(action)
        val button = batch.notification.actions.single { it.title == label }
        val intent = shadowOf(button.actionIntent).savedIntent
        BatchActionReceiver().handle(app, intent)?.let { job -> runBlocking { job.join() } }
    }

    @Test
    fun `one dose posts alone`() {
        NotificationPoster.post(app, spec("a"))

        assertEquals(1, active().size)
        assertNull(batchOf())
    }

    @Test
    fun `two doses at one moment add a batch notification that counts and names them`() {
        NotificationPoster.post(app, spec("b"))
        NotificationPoster.post(app, spec("a"))

        assertEquals(3, active().size)
        val batch = assertNotNull(batchOf())
        assertEquals("2 doses due", batch.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Name a, Name b", batch.notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("Take all", "Snooze all", "Skip all"), batch.notification.actions.map { it.title })
    }

    @Test
    fun `doses at different moments do not batch`() {
        NotificationPoster.post(app, spec("a", at = NINE))
        NotificationPoster.post(app, spec("b", at = NINE + 3_600_000))

        assertEquals(2, active().size)
    }

    @Test
    fun `tasks do not batch`() {
        NotificationPoster.post(app, spec("a", tier = Tier.Task))
        NotificationPoster.post(app, spec("b", tier = Tier.Task))

        assertEquals(2, active().size)
        assertNull(batchOf())
    }

    @Test
    fun `a task due with a dose is not counted in the batch`() {
        NotificationPoster.post(app, spec("chore", tier = Tier.Task))
        NotificationPoster.post(app, spec("a"))

        assertNull(batchOf())
    }

    @Test
    fun `a third dose updates the batch rather than adding another`() {
        for (id in listOf("a", "b", "c")) NotificationPoster.post(app, spec(id))

        assertEquals(4, active().size)
        assertEquals("3 doses due", batchOf()!!.notification.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    fun `each moment's buttons are its own`() {
        for (id in listOf("a", "b")) NotificationPoster.post(app, spec(id, at = NINE))
        for (id in listOf("c", "d")) NotificationPoster.post(app, spec(id, at = NINE + 3_600_000))

        fun ids(at: Long) = shadowOf(batchOf(at)!!.notification.actions[0].actionIntent).savedIntent.getStringArrayListExtra(BatchActionReceiver.EXTRA_REMINDER_IDS)
        assertEquals(setOf("a", "b"), ids(NINE)!!.toSet())
        assertEquals(setOf("c", "d"), ids(NINE + 3_600_000)!!.toSet())
    }

    @Test
    fun `Take all completes every dose and clears the shade`() {
        create("a", "b", "c")
        for (id in listOf("a", "b", "c")) NotificationPoster.post(app, spec(id))

        press(batchOf()!!, BatchAction.Take)

        val rows = runBlocking { container.instances.list(InstanceFilter(USER)) }
        assertEquals(setOf(InstanceStatus.Completed), rows.map { it.status }.toSet())
        assertEquals(3, rows.size)
        assertEquals(emptyList(), active())
    }

    @Test
    fun `Snooze all and Skip all do their own thing`() {
        create("a", "b")
        for (id in listOf("a", "b")) NotificationPoster.post(app, spec(id))
        press(batchOf()!!, BatchAction.Snooze)
        assertEquals(setOf(InstanceStatus.Snoozed), runBlocking { container.instances.list(InstanceFilter(USER)) }.map { it.status }.toSet())

        for (id in listOf("a", "b")) NotificationPoster.post(app, spec(id))
        press(batchOf()!!, BatchAction.Skip)
        assertEquals(setOf(InstanceStatus.Skipped), runBlocking { container.instances.list(InstanceFilter(USER)) }.map { it.status }.toSet())
    }

    @Test
    fun `dismissing leaves other moments and other doses alone`() {
        for (id in listOf("a", "b")) NotificationPoster.post(app, spec(id, at = NINE))
        for (id in listOf("c", "d")) NotificationPoster.post(app, spec(id, at = NINE + 3_600_000))

        NotificationPoster.dismiss(app, NINE, listOf("a"))

        assertNull(batchOf(NINE))
        assertTrue(active().any { it.tag == "b-$NINE" })
        assertTrue(active().none { it.tag == "a-$NINE" })
        assertNotNull(batchOf(NINE + 3_600_000))
    }

    private fun pressDose(id: String, label: String) {
        val button = active().single { it.tag == "$id-$NINE" }.notification.actions.single { it.title == label }
        BatchActionReceiver().handle(app, shadowOf(button.actionIntent).savedIntent)?.let { job -> runBlocking { job.join() } }
    }

    private fun statuses() = runBlocking { container.instances.list(InstanceFilter(USER)) }.associate { it.reminderId to it.status }

    @Test
    fun `a dose notification can be taken, snoozed or skipped`() {
        NotificationPoster.post(app, spec("a"))

        assertEquals(listOf("Take", "Snooze", "Skip"), active().single().notification.actions.map { it.title })
    }

    @Test
    fun `a task notification has no buttons`() {
        NotificationPoster.post(app, spec("a", tier = Tier.Task))

        assertNull(active().single().notification.actions)
    }

    @Test
    fun `Take on one dose answers only that dose and clears only its notification`() {
        create("a", "b")
        NotificationPoster.post(app, spec("a"))
        NotificationPoster.post(app, spec("b"))

        pressDose("a", "Take")

        assertEquals(mapOf("a" to InstanceStatus.Completed), statuses())
        assertTrue(active().none { it.tag == "a-$NINE" })
        assertTrue(active().any { it.tag == "b-$NINE" })
    }

    @Test
    fun `Skip and Snooze on one dose do their own thing`() {
        create("a", "b")
        NotificationPoster.post(app, spec("a"))
        NotificationPoster.post(app, spec("b"))

        pressDose("a", "Skip")
        pressDose("b", "Snooze")

        assertEquals(mapOf("a" to InstanceStatus.Skipped, "b" to InstanceStatus.Snoozed), statuses())
    }

    @Test
    fun `answering one of three leaves a batch of two, and one of two leaves no batch`() {
        create("a", "b", "c")
        for (id in listOf("a", "b", "c")) NotificationPoster.post(app, spec(id))

        pressDose("a", "Take")
        val batch = batchOf()!!
        assertEquals("2 doses due", batch.notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Name b, Name c", batch.notification.extras.getString(Notification.EXTRA_TEXT))

        // The rebuilt buttons answer the two that remain, not the three that were.
        press(batch, BatchAction.Take)
        assertEquals(mapOf("a" to InstanceStatus.Completed, "b" to InstanceStatus.Completed, "c" to InstanceStatus.Completed), statuses())
        assertEquals(emptyList(), active())
    }

    @Test
    fun `answering one of two removes the batch`() {
        create("a", "b")
        NotificationPoster.post(app, spec("a"))
        NotificationPoster.post(app, spec("b"))

        pressDose("a", "Take")

        assertNull(batchOf())
        assertEquals(1, active().size)
    }

    @Test
    fun `each dose's buttons are its own`() {
        NotificationPoster.post(app, spec("a"))
        NotificationPoster.post(app, spec("b"))

        fun ids(id: String): List<String>? =
            shadowOf(active().single { it.tag == "$id-$NINE" }.notification.actions[0].actionIntent).savedIntent.getStringArrayListExtra(BatchActionReceiver.EXTRA_REMINDER_IDS)
        assertEquals(listOf("a"), ids("a"))
        assertEquals(listOf("b"), ids("b"))
    }

    @Test
    fun `an intent that cannot be read does nothing`() {
        val receiver = BatchActionReceiver()

        assertNull(receiver.handle(app, Intent(BatchActionReceiver.ACTION_BATCH)))
        assertNull(receiver.handle(app, Intent("something.else").putExtra(BatchActionReceiver.EXTRA_ACTION, "take").putExtra(BatchActionReceiver.EXTRA_TIME, NINE).putStringArrayListExtra(BatchActionReceiver.EXTRA_REMINDER_IDS, arrayListOf("a"))))
        assertNull(receiver.handle(app, Intent(BatchActionReceiver.ACTION_BATCH).putExtra(BatchActionReceiver.EXTRA_ACTION, "explode").putExtra(BatchActionReceiver.EXTRA_TIME, NINE).putStringArrayListExtra(BatchActionReceiver.EXTRA_REMINDER_IDS, arrayListOf("a"))))
        assertNull(receiver.handle(app, Intent(BatchActionReceiver.ACTION_BATCH).putExtra(BatchActionReceiver.EXTRA_ACTION, "take").putStringArrayListExtra(BatchActionReceiver.EXTRA_REMINDER_IDS, arrayListOf("a"))))
        assertNull(receiver.handle(app, Intent(BatchActionReceiver.ACTION_BATCH).putExtra(BatchActionReceiver.EXTRA_ACTION, "take").putExtra(BatchActionReceiver.EXTRA_TIME, NINE)))
    }
}
