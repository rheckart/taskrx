package com.taskrx.mobile.notifications

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.notifications.RearmCause
import com.taskrx.core.notifications.medicationNotificationId
import com.taskrx.core.notifications.taskNotificationId
import com.taskrx.mobile.DAY
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import java.time.Instant
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

/*
 * Mirrors archive/expo-rn:apps/mobile/src/notifications/scheduler.test.ts. Everything below the
 * scheduler is real: Room, AlarmManager (Robolectric's), WorkManager (the test
 * initialisation). Only the recording wrappers observe.
 */
private val NOW = Instant.parse("2026-08-06T12:00:00Z").toEpochMilli()
private val NINE_AM = Instant.parse("2026-08-07T09:00:00Z").toEpochMilli()

@RunWith(RobolectricTestRunner::class)
class NotificationSchedulerTest {
    private fun dose(id: String, at: Long = NOW + HOUR) = id to onceTimes(at)

    @Test
    fun `arms a dose on the alarm clock and a task as a work request`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 2 * HOUR))

            scheduler.rescheduleAll(USER)

            val doseId = medicationNotificationId("med", NOW + HOUR)
            val taskId = taskNotificationId("chore", NOW + 2 * HOUR)
            assertEquals(listOf(doseId), alarmIds())
            assertNotNull(alarm(doseId).alarmClockInfo, "a dose takes setAlarmClock")
            assertEquals(NOW + HOUR, alarm(doseId).alarmClockInfo?.triggerTime)
            assertEquals(listOf(taskId), workIds())
        }

    private suspend fun NotifFixture.answerEarly(id: String, at: Long, status: InstanceStatus) {
        val kind = if (id == "chore") ReminderKind.Task else ReminderKind.Medication
        fx.container.instances.create(
            CreateReminderInstanceInput(USER, id, kind, at, status, if (status == InstanceStatus.Completed) NOW else null),
        )
    }

    @Test
    fun `a dose already completed early is not armed and another still is`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("other", ReminderKind.Medication, onceTimes(NOW + HOUR))
            answerEarly("med", NOW + HOUR, InstanceStatus.Completed)

            scheduler.rescheduleAll(USER)

            assertEquals(listOf(medicationNotificationId("other", NOW + HOUR)), alarmIds())
        }

    @Test
    fun `a dose skipped early is not armed, a task completed early is not queued`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            answerEarly("med", NOW + HOUR, InstanceStatus.Skipped)
            answerEarly("chore", NOW + HOUR, InstanceStatus.Completed)

            scheduler.rescheduleAll(USER)

            assertTrue(alarmIds().isEmpty(), "armed: ${alarmIds()}")
            assertTrue(workIds().isEmpty(), "queued: ${workIds()}")
        }

    @Test
    fun `answering early cancels the alarm that was already armed`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            assertEquals(listOf(medicationNotificationId("med", NOW + HOUR)), alarmIds())

            answerEarly("med", NOW + HOUR, InstanceStatus.Completed)
            scheduler.rescheduleAll(USER)

            assertTrue(alarmIds().isEmpty(), "armed: ${alarmIds()}")
        }

    @Test
    fun `a pending or snoozed instance does not silence its dose`() =
        withNotifs(NOW) {
            fx.reminder("a", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("b", ReminderKind.Medication, onceTimes(NOW + HOUR))
            answerEarly("a", NOW + HOUR, InstanceStatus.Pending)
            answerEarly("b", NOW + HOUR, InstanceStatus.Snoozed)

            scheduler.rescheduleAll(USER)

            assertEquals(
                listOf(medicationNotificationId("a", NOW + HOUR), medicationNotificationId("b", NOW + HOUR)),
                alarmIds().sorted(),
            )
        }

    @Test
    fun `never arms a task with AlarmManager`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, dailyTimes(listOf("09:00", "13:00", "18:00")))

            scheduler.rescheduleAll(USER)

            assertTrue(workIds().isNotEmpty())
            assertTrue(alarmIds().isEmpty(), "a task must not occupy an alarm slot: ${alarmIds()}")
            assertEquals(0, api.inexactCalls)
        }

    @Test
    fun `arms a task with a delay that reaches its time`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 3 * HOUR))
            scheduler.rescheduleAll(USER)

            val info = workManager.getWorkInfosForUniqueWork(taskNotificationId("chore", NOW + 3 * HOUR)).get().single()
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED, info.state)
            assertEquals(3 * HOUR, info.initialDelayMillis)
        }

    @Test
    fun `a clock change re-arms a task with the delay the new clock implies`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 3 * HOUR))
            scheduler.rescheduleAll(USER)
            val id = taskNotificationId("chore", NOW + 3 * HOUR)
            assertEquals(3 * HOUR, workManager.getWorkInfosForUniqueWork(id).get().single().initialDelayMillis)

            fx.now = NOW + HOUR
            scheduler.rescheduleAll(USER, RearmCause.TimeChanged)

            val infos = workManager.getWorkInfosForUniqueWork(id).get().filter { !it.state.isFinished }
            assertEquals(2 * HOUR, infos.single().initialDelayMillis)
        }

    @Test
    fun `a task that has fired is no longer armed and its notification is posted`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            val id = taskNotificationId("chore", NOW + HOUR)
            val work = workManager.getWorkInfosForUniqueWork(id).get().single()

            androidx.work.testing.WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(work.id)

            assertTrue(tasks.armed().isEmpty())
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            assertEquals("task_reminders", shadowOf(manager).getNotification(id, 0).channelId)
        }

    @Test
    fun `cancels what is in neither desired set, in both Tiers`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 2 * HOUR))
            scheduler.rescheduleAll(USER)
            resetRecordings()

            fx.container.reminders.softDelete("med")
            fx.container.reminders.softDelete("chore")
            scheduler.rescheduleAll(USER)

            assertEquals(listOf(medicationNotificationId("med", NOW + HOUR)), medication.cancelledIds)
            assertEquals(listOf(taskNotificationId("chore", NOW + 2 * HOUR)), tasks.cancelledIds)
            assertTrue(alarmIds().isEmpty())
            assertTrue(workIds().isEmpty())
        }

    @Test
    fun `leaves an armed task alone while arming a dose, and a dose alone while arming a task`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 2 * HOUR))
            scheduler.rescheduleAll(USER)
            resetRecordings()

            fx.reminder("med2", ReminderKind.Medication, onceTimes(NOW + 3 * HOUR))
            fx.reminder("chore2", ReminderKind.Task, onceTimes(NOW + 4 * HOUR))
            scheduler.rescheduleAll(USER)

            assertEquals(listOf(medicationNotificationId("med2", NOW + 3 * HOUR)), medication.armedIds)
            assertEquals(listOf(taskNotificationId("chore2", NOW + 4 * HOUR)), tasks.armedIds)
            assertTrue(medication.cancelledIds.isEmpty())
            assertTrue(tasks.cancelledIds.isEmpty())
            assertEquals(2, alarmIds().size)
            assertEquals(2, workIds().size)
        }

    @Test
    fun `still cancels a task whose reminder is gone`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            fx.container.reminders.softDelete("chore")
            scheduler.rescheduleAll(USER)
            assertTrue(workIds().isEmpty())
        }

    @Test
    fun `cancels and creates nothing on a second run with unchanged data`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00", "21:00")))
            fx.reminder("chore", ReminderKind.Task, dailyTimes(listOf("10:00")))

            val first = scheduler.rescheduleAll(USER)
            assertTrue(first.create.isNotEmpty())
            resetRecordings()

            val second = scheduler.rescheduleAll(USER)

            assertTrue(second.create.isEmpty() && second.cancel.isEmpty())
            assertTrue(medication.armedIds.isEmpty() && medication.cancelledIds.isEmpty())
            assertTrue(tasks.armedIds.isEmpty() && tasks.cancelledIds.isEmpty())
        }

    @Test
    fun `a flood of tasks neither displaces a dose nor exceeds its own budget`() =
        withNotifs(NOW) {
            fx.reminder("insulin", ReminderKind.Medication, dailyTimes(listOf("08:00", "14:00", "21:00")))
            repeat(40) { fx.reminder("t$it", ReminderKind.Task, dailyTimes(listOf("01:00", "02:00", "03:00"))) }

            scheduler.rescheduleAll(USER)

            assertEquals(32, workIds().size)
            val insulin = alarmIds().filter { it.startsWith("insulin-") }
            assertEquals(64, insulin.size, "the dose horizon is full and untouched")
        }

    @Test
    fun `holds the medication horizon to 64 alarms`() =
        withNotifs(NOW) {
            fx.reminder("m1", ReminderKind.Medication, dailyTimes(listOf("08:00", "12:00", "20:00")))
            scheduler.rescheduleAll(USER)
            assertEquals(64, alarmIds().size)
        }

    @Test
    fun `an armed record the system has lost is armed again`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            val id = medicationNotificationId("med", NOW + HOUR)

            // A force-stop cancels the PendingIntent; the ledger still claims the alarm.
            loseAllAlarms()
            resetRecordings()
            scheduler.rescheduleAll(USER)

            assertEquals(listOf(id), medication.armedIds)
            assertEquals(listOf(id), alarmIds())
        }

    @Test
    fun `cancelForReminder clears all three namespaces for one reminder only`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("keep", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            scheduler.snooze("med", NOW + HOUR, NOW + 2 * HOUR)

            scheduler.cancelForReminder("med")
            scheduler.cancelForReminder("chore")

            assertEquals(listOf(medicationNotificationId("keep", NOW + HOUR)), alarmIds())
            assertTrue(workIds().isEmpty())
            assertTrue(snoozes.all().isEmpty())
        }
}

@RunWith(RobolectricTestRunner::class)
class SnoozeTest {
    @Test
    fun `a snooze survives a reschedule`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)

            assertTrue(scheduler.snooze("med", NOW + HOUR, NOW + 2 * HOUR))
            scheduler.rescheduleAll(USER)
            scheduler.rescheduleAll(USER)

            val snoozeId = "snooze-${medicationNotificationId("med", NOW + HOUR)}-${NOW + 2 * HOUR}"
            assertTrue(snoozeId in alarmIds(), "reopening the app must not cancel a deferred reminder: ${alarmIds()}")
        }

    @Test
    fun `a snoozed dose takes the alarm clock`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.snooze("med", NOW + HOUR, NOW + 2 * HOUR)
            val id = alarmIds().single()
            assertNotNull(alarm(id).alarmClockInfo)
            assertEquals(NOW + 2 * HOUR, alarm(id).alarmClockInfo?.triggerTime)
        }

    @Test
    fun `a snoozed task is a work request and never an alarm`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            assertTrue(scheduler.snooze("chore", NOW + HOUR, NOW + 2 * HOUR))
            assertTrue(alarmIds().isEmpty())
            assertEquals(1, workIds().size)
        }

    @Test
    fun `arms nothing for a reminder this device cannot find`() =
        withNotifs(NOW) {
            assertFalse(scheduler.snooze("ghost", NOW, NOW + HOUR))
            assertTrue(alarmIds().isEmpty())
            assertTrue(workIds().isEmpty())
        }

    @Test
    fun `a fired snooze is dropped on the next pass`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + 10 * DAY))
            scheduler.snooze("med", NOW, NOW + HOUR)
            fx.now = NOW + 2 * HOUR
            scheduler.rescheduleAll(USER)
            assertTrue(snoozes.all().isEmpty())
            assertTrue(alarmIds().none { it.startsWith("snooze-") })
            assertNull(ledger.all().firstOrNull { it.id.startsWith("snooze-") })
        }
}

@RunWith(RobolectricTestRunner::class)
class ExactAlarmDegradationTest {
    @Test
    fun `revoking the permission cancels the exact alarms, and the next pass arms them again inexactly`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            val id = alarmIds().single()
            assertNotNull(alarm(id).alarmClockInfo)

            // The system cancels every exact alarm on revoke; the PendingIntent and the ledger survive.
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            alarmManager.cancel(alarm(id).operation!!)
            assertTrue(alarmIds().isEmpty())
            scheduler.rescheduleAll(USER)

            assertEquals(listOf(id), alarmIds())
            assertNull(alarm(id).alarmClockInfo)
            assertTrue(alarm(id).allowWhileIdle)
        }

    @Test
    fun `a task late behind Doze is not cancelled by a foreground pass`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            val id = taskNotificationId("chore", NOW + HOUR)

            fx.now = NOW + HOUR + 20 * 60_000L // due 20 minutes ago, work has not run
            scheduler.rescheduleAll(USER)

            assertEquals(listOf(id), workIds())
            val work = workManager.getWorkInfosForUniqueWork(id).get().single()
            androidx.work.testing.WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(work.id)
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            assertNotNull(shadowOf(manager).getNotification(id, 0))
        }

    @Test
    fun `a task snooze late behind Doze is not cancelled by the prune`() =
        withNotifs(NOW) {
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 10 * DAY))
            scheduler.snooze("chore", NOW, NOW + HOUR)
            fx.now = NOW + HOUR + 60_000L
            scheduler.rescheduleAll(USER)
            assertTrue(workIds().any { it.startsWith("snooze-") })
        }

    @Test
    fun `denied exact alarms degrade a dose to an inexact alarm instead of dropping it`() =
        withNotifs(NOW) {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))

            scheduler.rescheduleAll(USER)

            val id = medicationNotificationId("med", NOW + HOUR)
            val alarm = alarm(id)
            assertNull(alarm.alarmClockInfo, "no setAlarmClock without the permission")
            assertTrue(alarm.allowWhileIdle, "setAndAllowWhileIdle")
            assertEquals(NOW + HOUR, alarm.triggerAtMs)
            assertTrue(ledger.all().single().degraded)
        }

    @Test
    fun `a refusal at the call, after the check passed, degrades the same way`() =
        withNotifs(NOW) {
            api.refuseExact = true
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))

            scheduler.rescheduleAll(USER)

            assertEquals(1, api.inexactCalls)
            assertNull(alarm(alarmIds().single()).alarmClockInfo)
        }

    @Test
    fun `a task is unaffected by the denial`() =
        withNotifs(NOW) {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            assertEquals(1, workIds().size)
            assertTrue(alarmIds().isEmpty())
        }

    @Test
    fun `granting the permission later upgrades the degraded alarm in place`() =
        withNotifs(NOW) {
            ShadowAlarmManager.setCanScheduleExactAlarms(false)
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            val id = alarmIds().single()
            assertNull(alarm(id).alarmClockInfo)

            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            resetRecordings()
            scheduler.rescheduleAll(USER, RearmCause.ExactAlarmPermissionChanged)

            assertEquals(listOf(id), medication.armedIds)
            assertEquals(listOf(id), alarmIds(), "replaced, not duplicated")
            assertNotNull(alarm(id).alarmClockInfo)
            assertFalse(ledger.all().single().degraded)
        }
}

@RunWith(RobolectricTestRunner::class)
class RearmTest {
    @Test
    fun `after a reboot every dose is armed again but the work queue is left alone`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, dailyTimes(listOf("09:00")))
            fx.reminder("chore", ReminderKind.Task, dailyTimes(listOf("10:00")))
            scheduler.rescheduleAll(USER)
            val doses = alarmIds().toSet()
            val taskWork = workIds().toSet()
            assertTrue(doses.isNotEmpty() && taskWork.isNotEmpty())

            loseAllAlarms()
            assertTrue(alarmIds().isEmpty())
            resetRecordings()
            scheduler.rescheduleAll(USER, RearmCause.Boot)

            assertEquals(doses, alarmIds().toSet())
            assertEquals(doses, medication.armedIds.toSet())
            assertTrue(tasks.armedIds.isEmpty(), "WorkManager persists across reboot")
            assertEquals(taskWork, workIds().toSet())
        }

    @Test
    fun `the boot pass does not trust a ledger the reboot made stale`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            // The reboot cleared the alarms but the PendingIntent registry here still
            // answers: only the cause says the record is stale.
            for (alarm in alarmShadow.scheduledAlarms.toList()) alarmManager.cancel(alarm.operation!!)
            resetRecordings()

            scheduler.rescheduleAll(USER, RearmCause.Boot)

            assertEquals(1, medication.armedIds.size)
            assertEquals(1, alarmIds().size)
        }

    @Test
    fun `an update re-arms the doses`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            resetRecordings()
            scheduler.rescheduleAll(USER, RearmCause.PackageReplaced)
            assertEquals(1, medication.armedIds.size)
            assertTrue(tasks.armedIds.isEmpty())
        }

    @Test
    fun `a clock change re-arms both Tiers`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            fx.reminder("chore", ReminderKind.Task, onceTimes(NOW + 2 * HOUR))
            scheduler.rescheduleAll(USER)
            resetRecordings()

            scheduler.rescheduleAll(USER, RearmCause.TimeChanged)

            assertEquals(1, medication.armedIds.size)
            assertEquals(1, tasks.armedIds.size)
            assertEquals(1, workIds().size, "replaced by unique name, not duplicated")
        }

    @Test
    fun `a stale record is still cancelled during a rearm`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + HOUR))
            scheduler.rescheduleAll(USER)
            fx.container.reminders.softDelete("med")
            scheduler.rescheduleAll(USER, RearmCause.TimeChanged)
            assertTrue(alarmIds().isEmpty())
        }

    @Test
    fun `a pending snooze is brought back after a reboot`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + 10 * DAY))
            scheduler.snooze("med", NOW, NOW + 2 * HOUR)
            val id = alarmIds().single()

            loseAllAlarms()
            assertTrue(alarmIds().isEmpty())
            scheduler.rescheduleAll(USER, RearmCause.Boot)

            assertTrue(id in alarmIds())
        }

    @Test
    fun `a permission change re-arms no snooze`() =
        withNotifs(NOW) {
            fx.reminder("med", ReminderKind.Medication, onceTimes(NOW + 10 * DAY))
            scheduler.snooze("med", NOW, NOW + 2 * HOUR)
            resetRecordings()
            scheduler.rescheduleAll(USER, RearmCause.ExactAlarmPermissionChanged)
            assertTrue(medication.armedIds.none { it.startsWith("snooze-") })
        }
}
