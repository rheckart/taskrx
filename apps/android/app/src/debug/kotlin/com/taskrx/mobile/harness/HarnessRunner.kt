package com.taskrx.mobile.harness

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.domain.weeklyTimes
import com.taskrx.core.notifications.MAX_MEDICATION_TRIGGERS
import com.taskrx.core.notifications.MAX_TASK_TRIGGERS
import com.taskrx.core.notifications.derivePermissionStatus
import com.taskrx.core.notifications.PermissionSnapshotInput
import com.taskrx.core.notifications.taskIdPrefixFor
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.Codecs
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.data.SyncQueueRepository
import com.taskrx.mobile.data.db.ReminderEntity
import com.taskrx.mobile.data.db.SyncStatus
import com.taskrx.mobile.data.db.TaskRxDatabase
import com.taskrx.mobile.notifications.NotificationScheduler
import com.taskrx.mobile.notifications.PermissionPlatform
import com.taskrx.mobile.notifications.TierGateway
import java.time.Instant
import java.time.ZoneId

/** Everything the actions touch, as parameters so a test can run them over Robolectric and in-memory Room. */
class HarnessDeps(
    val database: TaskRxDatabase,
    val reminders: RemindersRepository,
    val instances: ReminderInstancesRepository,
    val syncQueue: SyncQueueRepository,
    val scheduler: NotificationScheduler,
    val medication: TierGateway,
    val tasks: TierGateway,
    val permissions: PermissionPlatform,
    /** Whose data the app shows, and so whose Reminders the foreground and boot passes arm. */
    val userId: () -> String,
    /** The authenticated subject, or null. The signed-in actions refuse without one. */
    val signedInUserId: () -> String?,
    val clock: Clock,
    val zone: () -> ZoneId,
    /** Removes notifications already in the shade. */
    val cancelPosted: () -> Unit,
    val syncStatus: () -> String,
)

/** Name prefix for reminders the harness creates, and the only thing `cleanup-seeded` matches on. */
const val HARNESS_REMINDER_MARKER = "[harness]"

/** Rendered by the answer screen under the name, so a case can assert both. */
const val HARNESS_REMINDER_DOSAGE = "1 tablet"

/**
 * Stored in a rig Reminder's `instructions`: the local-only actions' rows, told apart from
 * the signed-in seeds (which `cleanup-seeded` and `purge-seeded` own) so neither family's
 * cleanup can reach the other's data.
 */
const val RIG_TAG = "harness-rig"

/** The reply every signed-in action gives with no subject; the script greps one `signed_in=false` to call a case never-run. */
const val SIGNED_OUT_REPLY = "signed_in=false user_id=none"

/** Mon/Wed/Fri: non-contiguous, so a scheduler that ignored the weekdays and went daily would fail the case. */
private val WEEKLY_DAYS = listOf(1, 3, 5)
private const val WEEKLY_TIME = "09:00"
private const val WEEKLY_COUNT = 3

/**
 * Larger than BOTH budgets. More than the task budget so the task Tier fills completely
 * (until it does, "the dose is still armed" says nothing), and more than the medication
 * budget because that is the one a Kind-blind scheduler would be ranking both Kinds
 * inside: with fewer tasks than that it arms every task AND the dose, and the failure the
 * case exists to catch cannot occur.
 */
const val FLOOD_TASK_COUNT = 80

/**
 * The adb harness's actions, as the Expo app's `runHarnessAction` had them, with the same
 * reply strings (the script greps them).
 *
 * **Everything goes through the production path.** The Expo harness wrote straight to
 * notify-kit; here a "scheduled" notification is a real Reminder row (tagged [RIG_TAG])
 * armed by `NotificationScheduler.rescheduleAll`. That is not a convenience: the app runs
 * `rescheduleAll` on every foreground, whose cancel pass retracts anything it did not
 * derive from storage, and the boot receiver re-arms only from storage. A notification
 * armed any other way would be swept at the next deep link and could never survive case 5's
 * reboot. It is also the stronger test: the alarm under measurement is the one the
 * scheduler chose.
 *
 * Rig rows are written straight to the DAO, not through the repository, so they enqueue no
 * sync entries: they are rig data and must never be pushed to a backend.
 */
class HarnessRunner(private val deps: HarnessDeps) {
    suspend fun run(spec: HarnessSpec): String =
        when (spec.action) {
            HarnessAction.Schedule -> schedule(spec)
            HarnessAction.ScheduleWeekly -> scheduleWeekly(spec)
            HarnessAction.ScheduleTask -> scheduleTask(spec)
            HarnessAction.ScheduleTaskFlood -> scheduleTaskFlood(spec)
            HarnessAction.CancelAll -> {
                cancelAll()
                "cancelled all trigger notifications"
            }
            HarnessAction.PendingCount -> "pending_count=${armedIds().size}"
            HarnessAction.PendingIds -> "pending_ids=${armedIds().joinToString(",")}"
            HarnessAction.ExactAlarmStatus -> "exact_alarm_granted=${permissionSnapshot().exactAlarmGranted}"
            // The app's own reading of whether it may notify, WITHOUT prompting: a status read
            // that changed the state it reports would be useless to case 12, which measures
            // what the app believed before anybody was asked.
            HarnessAction.NotificationStatus ->
                permissionSnapshot().let {
                    "notification_status=${it.status.wire} notifications_granted=${it.notificationGranted} exact_alarm_granted=${it.exactAlarmGranted}"
                }
            HarnessAction.DbProbe -> dbProbe()
            HarnessAction.AuthStatus -> deps.signedInUserId()?.let { "signed_in=true user_id=$it" } ?: SIGNED_OUT_REPLY
            HarnessAction.SeedDueReminder -> signedIn { seedDueReminder(spec, it) }
            HarnessAction.InstanceAt -> signedIn { instanceAt(spec, it) }
            // Answers signed out, unlike its siblings: the queue is not user-scoped and the
            // depth is diagnostic. The two stay separate because they drain differently - a
            // dead-lettered row will never leave on reconnect.
            HarnessAction.QueueCount ->
                "queue_pending=${deps.syncQueue.pending().size} queue_dead=${deps.syncQueue.deadLettered().size}"
            HarnessAction.SyncStatus -> "sync_status=${deps.syncStatus()}"
            HarnessAction.CleanupSeeded -> signedIn { cleanupSeeded(it) }
            HarnessAction.PurgeSeeded -> signedIn { purgeSeeded(it) }
        }

    private suspend fun signedIn(block: suspend (String) -> String): String = deps.signedInUserId()?.let { block(it) } ?: SIGNED_OUT_REPLY

    private fun permissionSnapshot() =
        derivePermissionStatus(PermissionSnapshotInput(deps.permissions.notificationAuthorization(), deps.permissions.androidAlarm()))

    private suspend fun armedIds(): List<String> = (deps.medication.armed() + deps.tasks.armed()).map { it.id }

    // ---- Local-only: rig Reminders armed by the production scheduler --------------------

    /**
     * One dose at an exact moment. Case 2 holds the moment against the OS's "next alarm
     * clock", so `at=` is the real fire time, to the millisecond.
     */
    private suspend fun schedule(spec: HarnessSpec): String {
        val fireAt = deps.clock() + spec.delayMs
        rigReminder(spec.id, ReminderKind.Medication, "$HARNESS_REMINDER_MARKER ${spec.id}", onceTimes(fireAt))
        deps.scheduler.rescheduleAll(deps.userId())
        return "scheduled id=${spec.id} at=$fireAt delayMs=${spec.delayMs}"
    }

    /**
     * A genuinely weekly dose. Returns the three soonest fire times the scheduler armed, so
     * the script can check each against the DEVICE's clock and zone. Three rather than the
     * whole rolling horizon: the production path arms the soonest 64, and a reply that long
     * would be cut off by the script's 160-character read and corrupt the last timestamp.
     */
    private suspend fun scheduleWeekly(spec: HarnessSpec): String {
        val now = deps.clock()
        rigReminder(spec.id, ReminderKind.Medication, "$HARNESS_REMINDER_MARKER ${spec.id}", weeklyTimes(WEEKLY_DAYS, listOf(WEEKLY_TIME)), createdAt = now)
        deps.scheduler.rescheduleAll(deps.userId())
        val fireAts = fireTimesUnder(deps.medication, "${spec.id}-", Regex("\\d+")).take(WEEKLY_COUNT)
        return "weekly_fire_ats=${fireAts.joinToString(",")}"
    }

    /** One task, once. Reports the armed ids so the script can check they are in the task namespace. */
    private suspend fun scheduleTask(spec: HarnessSpec): String {
        val fireAt = deps.clock() + spec.delayMs
        rigReminder(spec.id, ReminderKind.Task, "$HARNESS_REMINDER_MARKER ${spec.id}", onceTimes(fireAt))
        deps.scheduler.rescheduleAll(deps.userId())
        val armed = armedUnder(deps.tasks.armed().map { it.id }, taskIdPrefixFor(spec.id), Regex("\\d+"))
        return "task_armed=${armed.size} task_ids=${armed.joinToString(",")} fire_at=$fireAt"
    }

    /**
     * 80 tasks, every one due BEFORE a single dose. A Kind-blind scheduler orders purely by
     * time, so with 80 tasks in the next few minutes it would fill its slots with them and
     * never arm the dose. `once`, not `daily`, because a daily schedule's next occurrence
     * depends on the wall clock, so "sooner than the dose" would hold or not depending on
     * when the case happened to run.
     *
     * Reports both counts, both budgets and the dose's own ids, so the script can check the
     * case's premises before reading a verdict out of it.
     */
    private suspend fun scheduleTaskFlood(spec: HarnessSpec): String {
        val now = deps.clock()
        val doseAt = now + spec.delayMs
        rigReminder(
            "${spec.id}-dose",
            ReminderKind.Medication,
            "$HARNESS_REMINDER_MARKER ${spec.id} dose",
            onceTimes(doseAt),
            dosage = HARNESS_REMINDER_DOSAGE,
            createdAt = now,
        )
        for (index in 0 until FLOOD_TASK_COUNT) {
            // A minute out and one second apart: every one sooner than the dose, no two sharing
            // an instant, so the ordering the case depends on is a fact about the fixture.
            rigReminder(
                "${spec.id}-task-$index",
                ReminderKind.Task,
                "$HARNESS_REMINDER_MARKER ${spec.id} task $index",
                onceTimes(now + 60_000 + index * 1_000L),
                createdAt = now,
            )
        }
        deps.scheduler.rescheduleAll(deps.userId())

        val doseIds = armedUnder(deps.medication.armed().map { it.id }, "${spec.id}-dose-", Regex("\\d+"))
        val taskIds = armedUnder(deps.tasks.armed().map { it.id }, "${taskIdPrefixFor(spec.id)}task-", Regex("\\d+-\\d+"))
        return "dose_armed=${doseIds.size} task_armed=${taskIds.size} task_budget=$MAX_TASK_TRIGGERS " +
            "dose_budget=$MAX_MEDICATION_TRIGGERS flood=$FLOOD_TASK_COUNT dose_ids=${doseIds.joinToString(",")} dose_at=$doseAt"
    }

    /** Cancels everything armed, and removes the rig rows that would re-arm it at the next foreground. */
    private suspend fun cancelAll() {
        deleteRigReminders()
        for (entry in deps.medication.armed()) deps.medication.cancel(entry.id)
        for (entry in deps.tasks.armed()) deps.tasks.cancel(entry.id)
        deps.cancelPosted()
    }

    private suspend fun fireTimesUnder(gateway: TierGateway, prefix: String, rest: Regex): List<Long> =
        armedUnder(gateway.armed().map { it.id }, prefix, rest).map { it.removePrefix(prefix).toLong() }.sorted()

    private suspend fun rigReminder(
        id: String,
        kind: ReminderKind,
        name: String,
        schedule: ScheduleTimes,
        dosage: String? = null,
        createdAt: Long = deps.clock(),
    ) {
        deps.database.reminderDao().upsert(
            ReminderEntity(
                id = id,
                userId = deps.userId(),
                convexId = null,
                kind = kind.wire,
                name = name,
                rxcui = null,
                dosage = dosage,
                instructions = RIG_TAG,
                schedule = Codecs.scheduleToColumn(schedule),
                priority = Priority.Normal.wire,
                tags = Codecs.stringsToColumn(emptyList()),
                pillsRemaining = null,
                pillsPerDose = null,
                refillReminderDays = null,
                caregiverIds = Codecs.stringsToColumn(emptyList()),
                active = true,
                createdAt = createdAt,
                updatedAt = createdAt,
                // Rig data is never pushed: no queue entry, and nothing pending to reconcile.
                syncStatus = SyncStatus.SYNCED,
            ),
        )
    }

    private suspend fun deleteRigReminders() {
        deps.database.withTransaction {
            val db = deps.database.openHelper.writableDatabase
            deleteReminders(db, db.stringColumn("SELECT id FROM reminders WHERE instructions = ?", RIG_TAG))
        }
    }

    // ---- Signed-in: real rows, as the authenticated user -----------------------------------

    /**
     * Seeds a real Reminder due shortly and arms it through the production scheduler. The
     * reported `fire_at` is read back out of what is ARMED, not computed from the schedule: a
     * computed time no alarm backs would send the caller to wait for a notification that was
     * never going to post, and then blame delivery.
     */
    private suspend fun seedDueReminder(spec: HarnessSpec, userId: String): String {
        val target = Instant.ofEpochMilli(nextWholeMinute(deps.clock() + spec.delayMs)).atZone(deps.zone())
        val hhmm = "%02d:%02d".format(target.hour, target.minute)
        val reminder =
            deps.reminders.create(
                CreateReminderInput(
                    userId = userId,
                    kind = ReminderKind.Medication,
                    name = "$HARNESS_REMINDER_MARKER ${spec.id}",
                    dosage = HARNESS_REMINDER_DOSAGE,
                    schedule = dailyTimes(listOf(hhmm)),
                ),
            )
        deps.scheduler.rescheduleAll(userId)
        val fireAts = fireTimesUnder(deps.medication, "${reminder.id}-", Regex("\\d+"))
        return "seeded reminder_id=${reminder.id} fire_at=${fireAts.firstOrNull()?.toString() ?: "none"} hhmm=$hhmm armed=${fireAts.size}"
    }

    /**
     * Instances at one exact `(reminder, scheduled time)` pairing, status-blind: case 9 reads
     * this back precisely because the dose was answered. `completed` is named separately
     * because an answered occurrence carries MORE than one instance (the screen re-loads
     * after the write and mints a fresh pending one), so "the first row" is arbitrary.
     */
    private suspend fun instanceAt(spec: HarnessSpec, userId: String): String {
        val found =
            deps.instances.list(InstanceFilter(userId = userId, reminderId = spec.id, since = spec.value, until = spec.value))
                .sortedBy { it.createdAt }
        val first = found.firstOrNull()
        val completed = found.firstOrNull { it.status.wire == "completed" }
        val statuses = found.joinToString(",") { it.status.wire }
        return "instance_at=${first?.id ?: "none"} status=${first?.status?.wire ?: "none"} count=${found.size} " +
            "completed=${completed?.id ?: "none"} statuses=${statuses.ifEmpty { "none" }}"
    }

    /**
     * Soft-deletes what the seeding made, then re-arms what is left. The reschedule is not
     * tidiness: a soft delete drops the row from the active set but does not touch the OS, so
     * the seeded alarms would go on firing on a device nobody is testing any more.
     */
    private suspend fun cleanupSeeded(userId: String): String {
        val seeded = deps.reminders.list(userId).filter { it.name.startsWith(HARNESS_REMINDER_MARKER) && it.instructions != RIG_TAG }
        for (reminder in seeded) deps.reminders.softDelete(reminder.id)
        deps.scheduler.rescheduleAll(userId)
        return "cleaned=${seeded.size}"
    }

    /**
     * Hard-deletes everything the seeding made: the Reminders (inactive ones too), their
     * instances and their sync entries. The app has no hard delete anywhere and should not
     * grow one - a medication a user removed is a record they may need - so this is rig
     * cleanup and stays in the rig. Delete the CONVEX rows first (`--purge` does), or a pull
     * can land between the two and write the Reminders straight back.
     */
    private suspend fun purgeSeeded(userId: String): String =
        deps.database.withTransaction {
            val db = deps.database.openHelper.writableDatabase
            val seeded =
                db.stringColumn("SELECT id, name FROM reminders WHERE user_id = ? AND instructions IS NOT ?", userId, RIG_TAG, column = 0, filterName = true)
            val purged = deleteReminders(db, seeded)
            "purged_reminders=${purged.reminders} purged_instances=${purged.instances} purged_queue=${purged.queue}"
        }

    // ---- Database probe ---------------------------------------------------------------------

    /**
     * Asks SQLite what issue #23 turns on, and writes nothing that outlives it. Two probes,
     * and their DIFFERENCE is the diagnosis: taking a write lock only needs no other writer,
     * committing also needs no readers, so a probe that stopped at the lock reports a healthy
     * database while every real write fails. The error is returned VERBATIM.
     */
    private fun dbProbe(): String {
        val db = deps.database.openHelper.writableDatabase
        val journalMode = pragma(db, "journal_mode")
        val busyTimeout = pragma(db, "busy_timeout")

        val lockable =
            attempt {
                db.beginTransactionNonExclusive()
                db.endTransaction()
            }
        val writable =
            attempt {
                db.execSQL("CREATE TABLE IF NOT EXISTS _db_probe (id INTEGER PRIMARY KEY)")
                db.execSQL("INSERT INTO _db_probe (id) VALUES (1)")
                db.execSQL("DELETE FROM _db_probe WHERE id = 1")
            }
        val error = (writable.second ?: lockable.second ?: "none").replace(Regex("\\s+"), " ")
        return "db_writable=${if (writable.first) "yes" else "no"} db_lockable=${if (lockable.first) "yes" else "no"} " +
            "journal_mode=$journalMode busy_timeout=$busyTimeout db_error=$error"
    }

    private fun pragma(db: SupportSQLiteDatabase, name: String): String =
        try {
            db.query("PRAGMA $name").use { if (it.moveToFirst()) it.getString(0) else "none" }
        } catch (e: Exception) {
            "error:${e.message}"
        }

    private fun attempt(body: () -> Unit): Pair<Boolean, String?> =
        try {
            body()
            true to null
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
}

/**
 * The armed ids under [prefix] whose remainder matches [rest] entirely. A `startsWith` alone
 * cannot be COUNTED with: a reminder `t-1-other` produces `task-t-1-other-...`, which starts
 * with `task-t-1-`, and case 11 concludes "the task budget is full" from a count, so an
 * inflated one reports proof where there was none.
 */
fun armedUnder(ids: List<String>, prefix: String, rest: Regex): List<String> =
    ids.filter { it.startsWith(prefix) && rest.matches(it.removePrefix(prefix)) }

/**
 * The first whole local minute at or after [ms]. Rounds UP: the schedule engine has minute
 * resolution, and rounding down hands back an occurrence sooner than requested - a case
 * whose setup is still running when the alarm fires reports a delivery failure that did
 * not happen.
 */
fun nextWholeMinute(ms: Long): Long = -Math.floorDiv(-ms, 60_000L) * 60_000L

internal class Purged(val reminders: Int, val instances: Int, val queue: Int)

/**
 * Deletes [reminderIds] with their instances and sync entries, and reports SQLite's own
 * `changes`, never the length of an id list (the queue delete is handed one id per reminder
 * AND per instance, and counting those overstated a one-row queue as 29).
 *
 * Queue first: an entry left behind names a row that no longer exists, and the worker would
 * spend all five attempts failing to resolve it. Parents last: instances reference them.
 */
internal fun deleteReminders(db: SupportSQLiteDatabase, reminderIds: List<String>): Purged {
    if (reminderIds.isEmpty()) return Purged(0, 0, 0)
    val instanceIds = reminderIds.chunked(CHUNK).flatMap { chunk ->
        db.stringColumn("SELECT id FROM reminder_instances WHERE reminder_id IN (${placeholders(chunk.size)})", *chunk.toTypedArray())
    }
    val queue = (reminderIds + instanceIds).chunked(CHUNK).sumOf { db.delete("DELETE FROM sync_queue WHERE entity_id IN (${placeholders(it.size)})", it) }
    val instances = instanceIds.chunked(CHUNK).sumOf { db.delete("DELETE FROM reminder_instances WHERE id IN (${placeholders(it.size)})", it) }
    val reminders = reminderIds.chunked(CHUNK).sumOf { db.delete("DELETE FROM reminders WHERE id IN (${placeholders(it.size)})", it) }
    return Purged(reminders, instances, queue)
}

/** Under SQLite's 999 bound-variable limit. */
private const val CHUNK = 500

private fun placeholders(count: Int) = List(count) { "?" }.joinToString(",")

private fun SupportSQLiteDatabase.delete(sql: String, args: List<String>): Int =
    compileStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.bindString(index + 1, value) }
        statement.executeUpdateDelete()
    }

private fun SupportSQLiteDatabase.stringColumn(
    sql: String,
    vararg args: String,
    column: Int = 0,
    filterName: Boolean = false,
): List<String> =
    query(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                // `filterName`: the marker match is made here, with the same startsWith the
                // seeding uses, not in a SQL LIKE - one rule in one place, and no brackets in
                // a pattern language.
                if (!filterName || cursor.getString(1).startsWith(HARNESS_REMINDER_MARKER)) add(cursor.getString(column))
            }
        }
    }
