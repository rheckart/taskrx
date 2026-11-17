package com.taskrx.mobile.notifications

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.Tier
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.core.notifications.RearmCause
import com.taskrx.mobile.USER
import com.taskrx.mobile.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationsModuleTest {
    @Test
    fun `a pass that throws is logged and dropped, not thrown into the process`() =
        withFixture(1_000_000) {
            val escaped = mutableListOf<Throwable>()
            val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e })
            val module =
                NotificationsModule(
                    ApplicationProvider.getApplicationContext(),
                    container.reminders,
                    container.instances,
                    { error("clock broke") },
                    scope = scope,
                )
            module.currentUser.set(USER)

            module.onForeground()?.join()
            module.rearm(RearmCause.Boot)?.join()

            assertEquals(emptyList(), escaped)
        }

    @Test
    fun `a reschedule after a write that throws is logged and dropped, not thrown at the write that asked for it`() =
        withFixture(1_000_000) {
            val module =
                NotificationsModule(
                    ApplicationProvider.getApplicationContext(),
                    container.reminders,
                    container.instances,
                    { error("clock broke") },
                )

            module.rescheduleAfterWrite(USER)
        }

    private val nine = 9 * 3_600_000L

    private fun dose(id: String) =
        NotificationSpec(id = "$id-$nine", tier = Tier.Medication, fireAt = nine, title = "Name $id", body = "Time to take", reminderId = id, scheduledTime = nine)

    @Test
    fun `a dose answered on another device leaves the shade, with its batch`() =
        withFixture(nine) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val manager = app.getSystemService(NotificationManager::class.java)
            val module = NotificationsModule(app, container.reminders, container.instances, { nine })
            for (id in listOf("a", "b")) {
                container.reminders.create(CreateReminderInput(id = id, userId = USER, kind = ReminderKind.Medication, name = "Name $id", schedule = onceTimes(nine)))
                NotificationPoster.post(app, dose(id))
            }
            assertTrue(manager.activeNotifications.any { it.tag == "batch-$nine" }, "two doses at one moment show a batch")

            // What a pull writes when the desktop records the dose taken.
            val taken = container.instances.create(CreateReminderInstanceInput(USER, "a", ReminderKind.Medication, nine, InstanceStatus.Completed, nine))
            module.dismissAnsweredElsewhere(USER, listOf(taken))

            assertEquals(listOf("b-$nine"), manager.activeNotifications.map { it.tag }, "b is still due; one dose is no batch")
        }

    @Test
    fun `a moment with a row still open keeps its notification`() =
        withFixture(nine) {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val manager = app.getSystemService(NotificationManager::class.java)
            val module = NotificationsModule(app, container.reminders, container.instances, { nine })
            container.reminders.create(CreateReminderInput(id = "a", userId = USER, kind = ReminderKind.Medication, name = "Name a", schedule = onceTimes(nine)))
            NotificationPoster.post(app, dose("a"))
            container.instances.create(CreateReminderInstanceInput(USER, "a", ReminderKind.Medication, nine, InstanceStatus.Pending))
            val taken = container.instances.create(CreateReminderInstanceInput(USER, "a", ReminderKind.Medication, nine, InstanceStatus.Completed, nine))

            module.dismissAnsweredElsewhere(USER, listOf(taken))

            assertEquals(listOf("a-$nine"), manager.activeNotifications.map { it.tag })
        }
}
