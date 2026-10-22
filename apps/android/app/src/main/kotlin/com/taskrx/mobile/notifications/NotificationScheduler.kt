package com.taskrx.mobile.notifications

import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.isAnswerable
import com.taskrx.core.notifications.RearmCause
import com.taskrx.core.notifications.ReschedulePlan
import com.taskrx.core.notifications.Tier
import com.taskrx.core.notifications.belongsToReminder
import com.taskrx.core.notifications.desiredNotifications
import com.taskrx.core.notifications.planReschedule
import com.taskrx.core.notifications.snoozeSpec
import com.taskrx.core.notifications.snoozesToRearm
import com.taskrx.core.notifications.tiersToRearm
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.InstanceFilter
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors archive/expo-rn:apps/mobile/src/notifications/scheduler.ts and snooze.ts. Which notifications
 * should exist, what to cancel and what to create are all :core's (`desiredNotifications`,
 * `planReschedule`); this class reads the store, asks each Tier's gateway what it holds,
 * and makes the calls.
 *
 * **Both Tiers are diffed in one pass.** A pass that knew about one Tier only would
 * compute the other's notifications as stale and cancel them.
 *
 * Serialized by a lock: a foreground and a boot receiver can overlap, and two passes
 * reading the same armed set would both create what is missing.
 */
class NotificationScheduler(
    private val reminders: RemindersRepository,
    private val instances: ReminderInstancesRepository,
    private val medication: TierGateway,
    private val tasks: TierGateway,
    private val snoozes: SnoozeStore,
    private val exactAllowed: () -> Boolean,
    private val clock: Clock,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val lock = Mutex()

    private fun gateway(tier: Tier): TierGateway =
        when (tier) {
            Tier.Medication -> medication
            Tier.Task -> tasks
        }

    /**
     * Reads the user's Reminders from the local store (never from Convex: that is why
     * reminders fire offline) and brings what is armed in line with them. [cause] is set
     * when the system has told us the armed state may be stale.
     */
    suspend fun rescheduleAll(userId: String, cause: RearmCause? = null): ReschedulePlan =
        // The READ is inside the lock, not before it. A pass that read first and then
        // waited its turn would diff against Reminders another pass had since changed,
        // and cancel what that pass had just armed (a foreground racing a write).
        lock.withLock { arm(userId, reminders.list(userId), cause) }

    private suspend fun arm(userId: String, list: List<Reminder>, cause: RearmCause?): ReschedulePlan {
        val now = clock()
        val rearm = if (cause == null) emptySet() else tiersToRearm(cause)

        // A snooze that has fired is just a leftover record; cancelling it also
        // clears the ledger's claim on it. Only a dose's: a task's work request that is due
        // but not yet run is late (Doze), not stale, and cancelling it would lose the
        // reminder. A task request that has run is finished and needs no cancel.
        snoozes.pruneExpired(now).filter { it.tier == Tier.Medication }.forEach { gateway(it.tier).cancel(it.id) }

        // A moment the person already completed or skipped (taken early, say) must not ring.
        val answered =
            instances.list(InstanceFilter(userId = userId, since = now))
                .filterNot { isAnswerable(it.status) }
                .mapTo(HashSet()) { it.reminderId to it.scheduledTime }
        val desired = desiredNotifications(list, now, zone(), answered)
        val armed = medication.armed() + tasks.armed()
        val plan = planReschedule(desired, armed, exactAllowed(), rearm)

        val tierOf = armed.associate { it.id to it.tier }
        for (id in plan.cancel) gateway(tierOf.getValue(id)).cancel(id)

        // `desired` is doses first, so if arming throws part-way, what is already
        // armed is the medication set.
        for (spec in plan.create) gateway(spec.tier).arm(spec)

        if (cause != null) {
            for (spec in snoozesToRearm(snoozes.all(), cause, now)) gateway(spec.tier).arm(spec)
        }
        return plan
    }

    /**
     * Defers one Occurrence. Returns false, arming nothing, for a Reminder this device
     * cannot find: a row of no known Kind has no Tier, and guessing "medication" is how a
     * task once took the alarm-clock primitive.
     *
     * [snoozeUntil] is an instant, not a duration, because the caller has already written
     * it to the Instance.
     */
    suspend fun snooze(reminderId: String, scheduledTime: Long, snoozeUntil: Long): Boolean {
        val reminder = reminders.get(reminderId) ?: return false
        val spec = snoozeSpec(reminder, scheduledTime, snoozeUntil, clock())
        lock.withLock {
            snoozes.put(spec)
            gateway(spec.tier).arm(spec)
        }
        return true
    }

    /**
     * Cancels the deferred reminder of one Occurrence, for an undo. Only that moment's: a
     * Reminder's other snoozes stay armed, which [cancelForReminder] would not allow. The
     * Occurrence's own notification, if it is still ahead, is armed again by the reschedule
     * that follows every write.
     */
    suspend fun cancelSnooze(reminderId: String, scheduledTime: Long) =
        lock.withLock {
            for (spec in snoozes.removeForOccurrence(reminderId, scheduledTime)) gateway(spec.tier).cancel(spec.id)
        }

    /** Cancels everything armed for [reminderId] in all three id namespaces. */
    suspend fun cancelForReminder(reminderId: String) =
        lock.withLock {
            val armed = medication.armed() + tasks.armed()
            for (entry in armed.filter { belongsToReminder(it.id, reminderId) }) gateway(entry.tier).cancel(entry.id)
            snoozes.removeForReminder(reminderId)
        }
}
