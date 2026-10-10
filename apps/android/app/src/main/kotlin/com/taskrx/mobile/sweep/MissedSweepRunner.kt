package com.taskrx.mobile.sweep

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import com.taskrx.core.sweep.PlanMissedSweepInput
import com.taskrx.core.sweep.SWEEP_LOOKBACK_MS
import com.taskrx.core.sweep.planMissedSweep
import com.taskrx.mobile.data.CreateReminderInstanceInput
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the sweep reads and writes. An interface so a test can widen the read-to-write window. */
interface SweepStore {
    /** Active Reminders only: an inactive one predicts no moments. */
    suspend fun activeReminders(userId: String): List<Reminder>

    /** Instances whose moment falls in `[since, until]`. */
    suspend fun instancesBetween(userId: String, since: Long, until: Long): List<ReminderInstance>

    suspend fun settleAsMissed(instanceId: String)

    suspend fun mintMissed(userId: String, reminderId: String, kind: ReminderKind, scheduledTime: Long)
}

class RoomSweepStore(
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
) : SweepStore {
    override suspend fun activeReminders(userId: String) = reminders.list(userId)

    override suspend fun instancesBetween(userId: String, since: Long, until: Long) =
        instances.list(InstanceFilter(userId = userId, since = since, until = until))

    override suspend fun settleAsMissed(instanceId: String) {
        instances.markMissed(instanceId)
    }

    override suspend fun mintMissed(
        userId: String,
        reminderId: String,
        kind: ReminderKind,
        scheduledTime: Long,
    ) {
        instances.create(
            CreateReminderInstanceInput(
                userId = userId,
                reminderId = reminderId,
                reminderKind = kind,
                scheduledTime = scheduledTime,
                status = InstanceStatus.Missed,
            ),
        )
    }
}

/**
 * The missed sweep's persistence pass: read, plan, write (apps/mobile/src/sweep/runner.ts).
 *
 * Every rule about what is Missed lives in [planMissedSweep] in :core, with the Grace
 * Period and lookback ADR 0005 settled. This class decides nothing: it reads the two
 * tables, hands them over and persists the verdicts. If a judgement about time ever
 * appears below, it is in the wrong place.
 *
 * It exists because an Instance is minted lazily, only when someone acts: until a sweep
 * runs, an Occurrence nobody answered has no row at all and then falls out of the
 * forward-looking window, so Adherence would score one dose in thirty as 100%.
 *
 * ## Why the calls are serialized
 *
 * A sweep runs on every foreground and plans from a snapshot of what is stored. Two
 * overlapping sweeps would both read "no Instance at 09:00" and both mint one, so the
 * fix for a vanished moment would be a moment recorded twice. Holding a lock means the
 * second reads the first's rows and plans nothing, which is also what makes running it
 * more often than necessary free. The lock is released on failure, so one failed sweep
 * does not poison the ones behind it, and the caller still sees its own exception.
 *
 * One runner must be shared by every caller (the [com.taskrx.mobile.AppContainer] owns it);
 * two runners would have two locks.
 */
class MissedSweepRunner(
    private val store: SweepStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val lock = Mutex()

    /** Sweeps this user's unanswered moments as of [now]; returns how many were recorded as Missed. */
    suspend fun run(userId: String, now: Long): Int = lock.withLock { sweep(userId, now) }

    private suspend fun sweep(userId: String, now: Long): Int {
        val reminders = store.activeReminders(userId)
        // The window the planner bounds itself by. Wider would cost rows it then discards;
        // narrower would hide answers that must block a second Instance at a settled moment.
        val instances = store.instancesBetween(userId, now - SWEEP_LOOKBACK_MS, now)

        val verdicts =
            planMissedSweep(
                PlanMissedSweepInput(
                    reminders = reminders,
                    instances = instances,
                    now = now,
                    gracePeriodMs = GRACE_PERIOD_MS,
                    lookbackMs = SWEEP_LOOKBACK_MS,
                    zone = zone(),
                ),
            )

        for (verdict in verdicts) {
            val settling = verdict.instanceId
            if (settling != null) {
                store.settleAsMissed(settling)
            } else {
                store.mintMissed(userId, verdict.reminderId, verdict.reminderKind, verdict.scheduledTime)
            }
        }
        return verdicts.size
    }
}
