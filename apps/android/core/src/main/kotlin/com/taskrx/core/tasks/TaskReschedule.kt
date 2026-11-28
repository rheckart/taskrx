package com.taskrx.core.tasks

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.isPausedAt
import com.taskrx.core.domain.moveRefusal
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Planning how a task occurrence is rescheduled to a chosen instant.
 *
 * A task can arrive at rescheduling in several states: untouched and ahead (moved cleanly via
 * `OccurrenceOverride`, or updating `OnceTimes` when pulled earlier), already moved once
 * (updating the existing override's target rather than stacking an inert override), or overdue /
 * already materialized as `Pending`, `Missed`, or `Snoozed` (deferred via a `Snoozed`
 * `ReminderInstance`, because `OccurrenceOverride` cannot move a moment past its Grace Period
 * or with an existing server row).
 *
 * Pure: no clock, no storage. The caller supplies `now` and persists the returned plan.
 */

/** What write will reschedule the occurrence to the chosen instant. */
sealed interface TaskReschedulePlan {
    /** Move an open occurrence via [com.taskrx.core.domain.OccurrenceOverride], keeping the recurring schedule intact. */
    data class MoveOverride(val from: Long, val to: Long) : TaskReschedulePlan

    /** Move a [OnceTimes] reminder's sole moment earlier when no Instance exists yet. */
    data class RescheduleOnce(val to: Long) : TaskReschedulePlan

    /** Defer an overdue, opened, or already-snoozed occurrence via a `Snoozed` Reminder Instance. */
    data class DeferInstance(val scheduledTime: Long, val until: Long) : TaskReschedulePlan
}

/** Why rescheduling to the target instant is refused. */
sealed interface TaskRescheduleRefusal {
    /** Only tasks may be rescheduled here; medication schedules are clinical instructions. */
    data object NotATask : TaskRescheduleRefusal

    /** Completed and skipped occurrences are settled answers and cannot be rescheduled. */
    data object AlreadyAnswered : TaskRescheduleRefusal

    /** The target equals the occurrence's effective current moment. */
    data object Unchanged : TaskRescheduleRefusal

    /** The target is not in the future relative to `now`. */
    data object InThePast : TaskRescheduleRefusal

    /** Recurring or opened occurrences cannot be moved earlier than their scheduled moment. */
    data object NotAfter : TaskRescheduleRefusal

    /** The target falls inside one of the reminder's pause windows. */
    data object InAPause : TaskRescheduleRefusal
}

/** Result of planning a task occurrence reschedule. */
sealed interface TaskRescheduleOutcome {
    data class Allowed(val plan: TaskReschedulePlan) : TaskRescheduleOutcome

    data class Refused(val reason: TaskRescheduleRefusal) : TaskRescheduleOutcome
}

/**
 * Decides whether [reminder]'s occurrence at [scheduledTime] may be rescheduled to [target] as of
 * [now], and which write accomplishes it.
 */
fun planTaskReschedule(
    reminder: Reminder,
    scheduledTime: Long,
    target: Long,
    now: Long,
    instances: List<ReminderInstance> = emptyList(),
    gracePeriodMs: Long = GRACE_PERIOD_MS,
): TaskRescheduleOutcome {
    if (reminder.kind != ReminderKind.Task) {
        return TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.NotATask)
    }
    if (instances.any { it.status == InstanceStatus.Completed || it.status == InstanceStatus.Skipped }) {
        return TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.AlreadyAnswered)
    }
    val currentMoment = instances.firstOrNull { it.status == InstanceStatus.Snoozed }?.snoozedUntil ?: scheduledTime
    if (target == currentMoment) {
        return TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.Unchanged)
    }
    if (target <= now) {
        return TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.InThePast)
    }
    if (isPausedAt(reminder.pauses, target)) {
        return TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.InAPause)
    }

    val baseFrom = reminder.overrides.firstOrNull { it.to == scheduledTime }?.from ?: scheduledTime
    if (moveRefusal(reminder, baseFrom, target, now, instances, gracePeriodMs) == null) {
        return TaskRescheduleOutcome.Allowed(TaskReschedulePlan.MoveOverride(from = baseFrom, to = target))
    }
    if (reminder.schedule is OnceTimes && instances.isEmpty() && target < baseFrom) {
        return TaskRescheduleOutcome.Allowed(TaskReschedulePlan.RescheduleOnce(to = target))
    }
    if (target < scheduledTime) {
        return TaskRescheduleOutcome.Refused(TaskRescheduleRefusal.NotAfter)
    }
    return TaskRescheduleOutcome.Allowed(TaskReschedulePlan.DeferInstance(scheduledTime = scheduledTime, until = target))
}

/**
 * Tomorrow's local date (relative to [now] in [zone]) at the wall-clock time of [referenceMoment].
 * Preserves the wall-clock hour/minute across DST transitions via [ZonedDateTime].
 */
fun tomorrowAtSameTime(referenceMoment: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
    val time = Instant.ofEpochMilli(referenceMoment).atZone(zone).toLocalTime()
    return ZonedDateTime.of(tomorrow, time, zone).toInstant().toEpochMilli()
}

/**
 * The local calendar date [days] after [referenceMoment] in [zone], at the same wall-clock time.
 */
fun plusDaysAtSameTime(referenceMoment: Long, days: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val dateTime = Instant.ofEpochMilli(referenceMoment).atZone(zone)
    return ZonedDateTime.of(dateTime.toLocalDate().plusDays(days), dateTime.toLocalTime(), zone).toInstant().toEpochMilli()
}
