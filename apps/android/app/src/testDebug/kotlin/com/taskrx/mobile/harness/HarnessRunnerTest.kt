package com.taskrx.mobile.harness

import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.notifications.ANDROID_ALARM_DISABLED
import com.taskrx.core.notifications.ANDROID_ALARM_ENABLED
import com.taskrx.core.notifications.MAX_MEDICATION_TRIGGERS
import com.taskrx.core.notifications.MAX_TASK_TRIGGERS
import com.taskrx.core.notifications.NOTIFICATION_AUTH_AUTHORIZED
import com.taskrx.core.notifications.NOTIFICATION_AUTH_DENIED
import com.taskrx.mobile.Fixture
import com.taskrx.mobile.HOUR
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.notifications.NotifFixture
import com.taskrx.mobile.notifications.PermissionPlatform
import com.taskrx.mobile.notifications.withNotifs
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private val NOW = Instant.parse("2026-08-06T12:00:30Z").toEpochMilli()

private class FakePlatform(var auth: Int = NOTIFICATION_AUTH_AUTHORIZED, var alarm: Int = ANDROID_ALARM_ENABLED) : PermissionPlatform {
    var prompts = 0

    override fun notificationAuthorization() = auth

    override fun androidAlarm() = alarm

    override fun requestNotifications(): Boolean {
        prompts++
        return true
    }

    override fun openNotificationSettings() = Unit

    override fun openAlarmSettings() = Unit
}

private class Rig(val n: NotifFixture, val signedIn: String? = null, val platform: FakePlatform = FakePlatform()) {
    var postedCancelled = 0
    val fx: Fixture get() = n.fx

    val runner =
        HarnessRunner(
            HarnessDeps(
                database = fx.database,
                reminders = fx.container.reminders,
                instances = fx.container.instances,
                syncQueue = fx.container.syncQueue,
                scheduler = n.scheduler,
                medication = n.medication,
                tasks = n.tasks,
                permissions = platform,
                userId = { USER },
                signedInUserId = { signedIn },
                clock = { fx.now },
                zone = { ZoneOffset.UTC },
                cancelPosted = { postedCancelled++ },
                syncStatus = { "idle" },
            ),
        )

    suspend fun run(action: String, id: String = "x", value: Long = 0, nonce: String = "n") =
        runner.run(parseHarnessSpec(listOf(action, id, value.toString(), nonce), fx.now))

    suspend fun field(reply: String, name: String): String = Regex("(?:^| )$name=([^ ]*)").find(reply)?.groupValues?.get(1) ?: error("no $name in: $reply")
}

private fun withRig(signedIn: String? = null, platform: FakePlatform = FakePlatform(), block: suspend Rig.() -> Unit) =
    withNotifs(NOW) { Rig(this, signedIn, platform).block() }

@RunWith(RobolectricTestRunner::class)
class HarnessRunnerTest {
    // ---- local-only ------------------------------------------------------------------------

    @Test
    fun `schedule arms a dose on the alarm clock at exactly the reported moment`() =
        withRig {
            val reply = run("schedule", "kill-test", 60_000)
            val at = field(reply, "at").toLong()

            assertEquals(NOW + 60_000, at)
            assertEquals("scheduled id=kill-test at=$at delayMs=60000", reply)
            // The script's case 2 holds this against the OS's next alarm clock.
            assertEquals(at, n.alarmManager.nextAlarmClock.triggerTime)
            assertEquals(listOf("kill-test-$at"), n.alarmIds())
        }

    @Test
    fun `a scheduled notification survives the foreground pass that every deep link triggers`() =
        withRig {
            run("schedule", "kill-test", 60_000)
            n.resetRecordings()

            n.scheduler.rescheduleAll(USER)

            assertEquals(emptyList(), n.medication.cancelledIds)
            assertEquals(1, n.alarmIds().size)
        }

    @Test
    fun `a scheduled notification is re-armed from storage after the system loses every alarm`() =
        withRig {
            run("schedule", "boot-test-0", 900_000)
            n.loseAllAlarms()
            assertEquals(emptyList(), n.alarmIds())

            n.scheduler.rescheduleAll(USER, com.taskrx.core.notifications.RearmCause.Boot)

            assertEquals(1, n.alarmIds().size)
        }

    @Test
    fun `schedule twice under one id replaces the Reminder rather than failing`() =
        withRig {
            run("schedule", "dup", 60_000)
            run("schedule", "dup", 120_000)

            assertEquals(1, fx.container.reminders.list(USER).size)
            assertEquals(1, n.alarmIds().size)
            assertEquals(NOW + 120_000, n.alarmManager.nextAlarmClock.triggerTime)
        }

    @Test
    fun `rig Reminders are never queued for sync`() =
        withRig {
            run("schedule", "a", 60_000)
            run("schedule-task", "b", 60_000)
            run("schedule-task-flood", "c", 600_000)

            assertEquals(0, fx.container.syncQueue.pending().size)
            assertEquals(emptyList(), fx.database.reminderDao().pendingWithoutQueueEntry())
        }

    @Test
    fun `weekly arms the production horizon and reports the three soonest on Mon Wed Fri at nine`() =
        withRig {
            val reply = run("schedule-weekly", "weekly-1")
            val fireAts = field(reply, "weekly_fire_ats").split(",").map { it.toLong() }

            assertEquals(3, fireAts.size)
            assertEquals(fireAts.sorted(), fireAts)
            for (ms in fireAts) {
                val at = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
                assertTrue(at.dayOfWeek.value % 7 in listOf(1, 3, 5), "weekday ${at.dayOfWeek}")
                assertEquals("09:00", "%02d:%02d".format(at.hour, at.minute))
            }
            // The production horizon is armed, not just the three reported.
            assertTrue(n.alarmIds().size > 3)
        }

    @Test
    fun `a task is armed in the task namespace and never takes the alarm clock`() =
        withRig {
            val reply = run("schedule-task", "task-tier-1", 90_000)
            val fireAt = field(reply, "fire_at").toLong()

            assertEquals(NOW + 90_000, fireAt)
            assertEquals("1", field(reply, "task_armed"))
            assertEquals("task-task-tier-1-$fireAt", field(reply, "task_ids"))
            assertEquals(null, n.alarmManager.nextAlarmClock)
            assertEquals(emptyList(), n.alarmIds())
        }

    @Test
    fun `a flood of tasks fills the task Tier and leaves the dose holding the alarm clock`() =
        withRig {
            val reply = run("schedule-task-flood", "flood-1", 600_000)

            assertEquals("1", field(reply, "dose_armed"))
            assertEquals(MAX_TASK_TRIGGERS.toString(), field(reply, "task_armed"))
            assertEquals(MAX_TASK_TRIGGERS.toString(), field(reply, "task_budget"))
            assertEquals(MAX_MEDICATION_TRIGGERS.toString(), field(reply, "dose_budget"))
            assertEquals("80", field(reply, "flood"))
            assertTrue(80 > MAX_MEDICATION_TRIGGERS, "the flood must exceed the medication budget or the case proves nothing")
            val doseAt = field(reply, "dose_at").toLong()
            assertEquals("flood-1-dose-$doseAt", field(reply, "dose_ids"))
            assertEquals(doseAt, n.alarmManager.nextAlarmClock.triggerTime)
        }

    @Test
    fun `a flood counts only its own ids`() =
        withRig {
            // A neighbour whose id merely starts with the flood's prefix, and which is due sooner
            // than the flood so it takes one of the 32 task slots, must not inflate the count.
            fx.reminder("flood-2-task-other", ReminderKind.Task, onceTimes(NOW + 30_000))

            val reply = run("schedule-task-flood", "flood-2", 600_000)

            assertEquals(MAX_TASK_TRIGGERS, n.tasks.armed().size)
            assertEquals((MAX_TASK_TRIGGERS - 1).toString(), field(reply, "task_armed"))
        }

    @Test
    fun `cancel-all clears both Tiers, the rig rows that would re-arm them, and the shade`() =
        withRig {
            run("schedule", "a", 60_000)
            run("schedule-task", "b", 60_000)
            assertEquals("pending_count=2", run("pending-count"))

            assertEquals("cancelled all trigger notifications", run("cancel-all"))
            n.scheduler.rescheduleAll(USER)

            assertEquals("pending_count=0", run("pending-count"))
            assertEquals(emptyList(), fx.container.reminders.list(USER))
            assertEquals(1, postedCancelled)
        }

    @Test
    fun `cancel-all leaves a signed-in seed alone`() =
        withRig {
            fx.reminder("seed", ReminderKind.Medication, onceTimes(NOW + HOUR), name = "$HARNESS_REMINDER_MARKER seed")
            run("schedule", "a", 60_000)

            run("cancel-all")

            assertEquals(listOf("seed"), fx.container.reminders.list(USER).map { it.id })
        }

    @Test
    fun `cancel-all removes the instances and queue entries a sweep minted for a rig Reminder`() =
        withRig {
            run("schedule", "swept", 60_000)
            val instance =
                fx.container.instances.create(CreateReminderInstanceInput(USER, "swept", ReminderKind.Medication, NOW + 60_000))
            assertTrue(fx.container.syncQueue.pending().any { it.entityId == instance.id })

            run("cancel-all")

            assertEquals(null, fx.container.instances.get(instance.id))
            assertEquals(0, fx.container.syncQueue.pending().size)
        }

    @Test
    fun `pending-ids lists both Tiers`() =
        withRig {
            run("schedule", "a", 60_000)
            run("schedule-task", "b", 60_000)

            val ids = run("pending-ids").removePrefix("pending_ids=").split(",")

            assertEquals(2, ids.size)
            assertTrue(ids.any { it.startsWith("a-") })
            assertTrue(ids.any { it.startsWith("task-b-") })
        }

    @Test
    fun `exact-alarm-status follows the platform in both states`() {
        val platform = FakePlatform()
        withRig(platform = platform) {
            assertEquals("exact_alarm_granted=true", run("exact-alarm-status"))
            platform.alarm = ANDROID_ALARM_DISABLED
            assertEquals("exact_alarm_granted=false", run("exact-alarm-status"))
        }
    }

    @Test
    fun `notification-status reports what the platform says and never asks`() {
        val platform = FakePlatform(auth = NOTIFICATION_AUTH_DENIED, alarm = ANDROID_ALARM_DISABLED)
        withRig(platform = platform) {
            assertEquals(
                "notification_status=denied notifications_granted=false exact_alarm_granted=false",
                run("notification-status"),
            )
            platform.auth = NOTIFICATION_AUTH_AUTHORIZED
            platform.alarm = ANDROID_ALARM_ENABLED
            assertEquals(
                "notification_status=authorized notifications_granted=true exact_alarm_granted=true",
                run("notification-status"),
            )
            assertEquals(0, platform.prompts)
        }
    }

    @Test
    fun `db-probe reports a writable lockable database and leaves nothing behind`() =
        withRig {
            val reply = run("db-probe")

            assertTrue(reply.startsWith("db_writable=yes db_lockable=yes journal_mode="), reply)
            assertTrue(reply.endsWith("db_error=none"), reply)
            assertTrue(Regex("busy_timeout=\\d+").containsMatchIn(reply), reply)
            val rows = fx.database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM _db_probe").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(0, rows)
        }

    @Test
    fun `db-probe reports the database's own error verbatim when it cannot write`() =
        withRig {
            fx.database.openHelper.writableDatabase.execSQL("CREATE TABLE _db_probe (id INTEGER PRIMARY KEY, must_have TEXT NOT NULL)")

            val reply = run("db-probe")

            assertTrue(reply.startsWith("db_writable=no "), reply)
            assertTrue("NOT NULL" in reply, reply)
        }

    @Test
    fun `queue-count and sync-status answer with nobody signed in`() =
        withRig {
            fx.reminder("r") // enqueues a create
            assertEquals("queue_pending=1 queue_dead=0 queue_other=0", run("queue-count"))
            assertEquals("sync_status=idle", run("sync-status"))
        }

    @Test
    fun `queue-count leaves out what another user queued, which this user's drain never sends`() =
        withRig {
            fx.reminder("mine")
            fx.container.reminders.create(
                CreateReminderInput(userId = "someone-else", kind = ReminderKind.Task, name = "theirs", schedule = dailyTimes(emptyList())),
            )

            assertEquals("queue_pending=1 queue_dead=0 queue_other=1", run("queue-count"))
        }

    // ---- signed-in ---------------------------------------------------------------------------

    @Test
    fun `every signed-in action refuses with one reply when nobody is signed in, and writes nothing`() =
        withRig {
            assertEquals("signed_in=false user_id=none", run("auth-status"))
            for (action in listOf("seed-due-reminder", "instance-at", "cleanup-seeded", "purge-seeded")) {
                assertEquals(SIGNED_OUT_REPLY, run(action, "x", 60_000), action)
            }
            assertEquals(emptyList(), fx.container.reminders.list(USER))
            assertEquals(0, fx.container.syncQueue.pending().size)
        }

    @Test
    fun `auth-status names the subject`() = withRig(signedIn = "user_abc") { assertEquals("signed_in=true user_id=user_abc", run("auth-status")) }

    @Test
    fun `seed-due-reminder creates a real Reminder and reports the moment it is actually armed for`() =
        withRig(signedIn = USER) {
            val reply = run("seed-due-reminder", "tap-1", 120_000)

            val seeded = fx.container.reminders.list(USER).single()
            assertEquals("$HARNESS_REMINDER_MARKER tap-1", seeded.name)
            assertEquals(HARNESS_REMINDER_DOSAGE, seeded.dosage)
            assertEquals(seeded.id, field(reply, "reminder_id"))
            // 12:00:30 + 120s = 12:02:30, rounded UP to the minute.
            val expected = Instant.parse("2026-08-06T12:03:00Z").toEpochMilli()
            assertEquals(expected, field(reply, "fire_at").toLong())
            assertEquals("12:03", field(reply, "hhmm"))
            assertEquals(expected, n.alarmManager.nextAlarmClock.triggerTime)
            assertTrue(field(reply, "armed").toInt() >= 1)
            assertTrue(fx.container.syncQueue.pending().isNotEmpty(), "a seed is a real write and syncs")
        }

    @Test
    fun `instance-at reports every instance at one pairing and names the completed one`() =
        withRig(signedIn = USER) {
            val seeded = field(run("seed-due-reminder", "t", 120_000), "reminder_id")
            val at = NOW + HOUR
            val first = fx.container.instances.create(CreateReminderInstanceInput(USER, seeded, ReminderKind.Medication, at))
            fx.now += 1
            val second = fx.container.instances.create(CreateReminderInstanceInput(USER, seeded, ReminderKind.Medication, at))
            fx.container.instances.markCompleted(second.id, fx.now)

            val reply = run("instance-at", seeded, at)

            assertEquals(first.id, field(reply, "instance_at"))
            assertEquals("pending", field(reply, "status"))
            assertEquals("2", field(reply, "count"))
            assertEquals(second.id, field(reply, "completed"))
            assertEquals("pending,completed", field(reply, "statuses"))
        }

    @Test
    fun `instance-at with nothing there says none`() =
        withRig(signedIn = USER) {
            assertEquals("instance_at=none status=none count=0 completed=none statuses=none", run("instance-at", "nobody", 1))
        }

    @Test
    fun `cleanup-seeded soft-deletes the seeds, re-arms what is left, and leaves rig rows and real ones`() =
        withRig(signedIn = USER) {
            run("seed-due-reminder", "t", 120_000)
            run("schedule", "rig", 600_000)
            fx.reminder("real", ReminderKind.Medication, onceTimes(NOW + 2 * HOUR), name = "Real pill")

            assertEquals("cleaned=1", run("cleanup-seeded"))

            assertEquals(setOf("rig", "real"), fx.container.reminders.list(USER).map { it.id }.toSet())
            assertEquals(2, n.alarmIds().size)
        }

    @Test
    fun `purge-seeded hard-deletes seeds with their instances and queue entries, counting what SQLite changed`() =
        withRig(signedIn = USER) {
            val id = field(run("seed-due-reminder", "t", 120_000), "reminder_id")
            fx.container.instances.create(CreateReminderInstanceInput(USER, id, ReminderKind.Medication, NOW + HOUR))
            run("cleanup-seeded") // soft-deleted rows must still be found
            fx.reminder("real", name = "Real pill")
            run("schedule", "rig", 600_000)

            val reply = run("purge-seeded")

            assertEquals("1", field(reply, "purged_reminders"))
            assertEquals("1", field(reply, "purged_instances"))
            // create + delete for the Reminder, create for the instance: three rows, not the length of an id list.
            assertEquals("3", field(reply, "purged_queue"))
            assertEquals(null, fx.container.reminders.get(id))
            assertNotNull(fx.container.reminders.get("real"))
            assertNotNull(fx.container.reminders.get("rig"))
            assertEquals(1, fx.container.syncQueue.pending().count { it.entityId == "real" })
        }

    @Test
    fun `purge-seeded with nothing to purge says so`() =
        withRig(signedIn = USER) { assertEquals("purged_reminders=0 purged_instances=0 purged_queue=0", run("purge-seeded")) }

    @Test
    fun `a daily seed is a daily Reminder at the rounded minute`() =
        withRig(signedIn = USER) {
            run("seed-due-reminder", "t", 120_000)
            assertEquals(dailyTimes(listOf("12:03")), fx.container.reminders.list(USER).single().schedule)
        }
}
