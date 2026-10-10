package com.taskrx.mobile.notifications

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.notifications.ArmedNotification
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class SchedulerReadLockTest {
    /**
     * A pass reads the Reminders INSIDE the lock. If it read first and then queued for the
     * lock, a pass that waited behind another would diff against Reminders written in the
     * meantime, and cancel what the writer's own pass had just armed: a foreground racing
     * a write (or a harness `schedule`) would retract it.
     */
    @Test
    fun `a pass queued behind another sees Reminders written while it waited`() =
        withNotifs(NOW) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val gated =
                object : TierGateway by medication {
                    override suspend fun armed(): List<ArmedNotification> {
                        entered.complete(Unit)
                        release.await()
                        return medication.armed()
                    }
                }
            val scheduler =
                NotificationScheduler(fx.container.reminders, gated, tasks, snoozes, api::canScheduleExactAlarms, { fx.now }, { java.time.ZoneOffset.UTC })

            coroutineScope {
                val first = async(Dispatchers.Default) { scheduler.rescheduleAll(USER) }
                entered.await()
                val second = async(Dispatchers.Default) { scheduler.rescheduleAll(USER) }
                delay(300) // long enough for a pass that reads before locking to have read
                fx.reminder("late", ReminderKind.Medication, onceTimes(NOW + HOUR))
                release.complete(Unit)

                first.await()
                assertEquals(1, second.await().create.size)
            }
            assertEquals(1, medication.armed().size)
        }
}
