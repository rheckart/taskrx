package com.taskrx.mobile.notifications

import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.notifications.NotificationSpec
import com.taskrx.core.notifications.RearmCause
import com.taskrx.core.notifications.medicationNotificationId
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
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

private const val DUE = 1_780_000_000_000L

/** What the phone does when it boots with a dose that came due while it was off. */
@RunWith(RobolectricTestRunner::class)
class DueWhileAwayModuleTest {
    private class Rig(val fx: Fixture) {
        val posted = mutableListOf<NotificationSpec>()
        val escaped = mutableListOf<Throwable>()
        var failPost = false

        val module =
            NotificationsModule(
                ApplicationProvider.getApplicationContext(),
                fx.container.reminders,
                fx.container.instances,
                { fx.now },
                scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob() + CoroutineExceptionHandler { _, e -> escaped += e }),
                post = { if (failPost) error("notify broke") else posted += it },
            ).also { it.currentUser.set(USER) }

        suspend fun boot() = module.rearm(RearmCause.Boot)?.join()
    }

    private fun rig(now: Long, block: suspend Rig.() -> Unit) = withFixture(now) { Rig(this).block() }

    @Test
    fun `a boot posts the dose that came due while the phone was off`() =
        rig(DUE + HOUR / 2) {
            fx.reminder("m1", ReminderKind.Medication, onceTimes(DUE), createdAt = DUE - HOUR)

            boot()

            assertEquals(listOf(medicationNotificationId("m1", DUE)), posted.map { it.id })
            assertEquals("Reminder m1", posted.single().title)
        }

    @Test
    fun `a dose already answered, skipped or snoozed is not posted again`() =
        rig(DUE + HOUR / 2) {
            for ((i, status) in listOf(InstanceStatus.Completed, InstanceStatus.Skipped, InstanceStatus.Snoozed).withIndex()) {
                fx.reminder("m$i", ReminderKind.Medication, onceTimes(DUE), createdAt = DUE - HOUR)
                val instance = fx.container.instances.create(CreateReminderInstanceInput(USER, "m$i", ReminderKind.Medication, DUE))
                when (status) {
                    InstanceStatus.Completed -> fx.container.instances.markCompleted(instance.id, DUE)
                    InstanceStatus.Skipped -> fx.container.instances.markSkipped(instance.id)
                    else -> fx.container.instances.markSnoozed(instance.id, DUE + HOUR)
                }
            }

            boot()

            assertTrue(posted.isEmpty(), posted.toString())
        }

    @Test
    fun `a dose older than the Grace Period is left to the sweep`() =
        rig(DUE + 3 * HOUR) {
            fx.reminder("m1", ReminderKind.Medication, onceTimes(DUE), createdAt = DUE - HOUR)

            boot()

            assertTrue(posted.isEmpty())
        }

    @Test
    fun `a task is not posted, and neither is anything for a package update or a clock change`() =
        rig(DUE + HOUR / 2) {
            fx.reminder("t1", ReminderKind.Task, onceTimes(DUE), createdAt = DUE - HOUR)
            fx.reminder("m1", ReminderKind.Medication, onceTimes(DUE), createdAt = DUE - HOUR)

            module.rearm(RearmCause.PackageReplaced)?.join()
            module.rearm(RearmCause.TimeChanged)?.join()
            module.rearm(RearmCause.ExactAlarmPermissionChanged)?.join()
            assertTrue(posted.isEmpty(), "only a boot means the phone was off")

            boot()
            assertEquals(listOf("m1"), posted.map { it.reminderId }, "and only the dose")
        }

    @Test
    fun `a notification that fails to post is logged, not thrown into the boot receiver`() =
        rig(DUE + HOUR / 2) {
            fx.reminder("m1", ReminderKind.Medication, onceTimes(DUE), createdAt = DUE - HOUR)
            failPost = true

            boot()

            assertEquals(emptyList(), escaped)
        }
}
