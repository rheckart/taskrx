package com.taskrx.core.tasks

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.schedule.latestOccurrenceBefore
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import com.taskrx.core.sweep.snoozeRanOut
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.timeline.ProjectTimelineInput
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.TimelineWindow
import com.taskrx.core.timeline.projectTimeline
import com.taskrx.core.timeline.timelineRowKey
import java.time.ZoneId

/*
 * The Tasks tab, assembled: which rows [sectionTasks] is given.
 *
 * The tab is not one day. Upcoming covers the week ahead and Overdue reaches back past the
 * sweep's seven days, so the rows come from three places:
 *
 *  1. A projection of the task Reminders over [taskWindow], seven local days back through
 *     seven ahead, with a larger occurrence cap so an hourly task is not cut short.
 *  2. Stored task Instances from before that window, read as they are, except that a snooze
 *     nobody came back to reads as the miss it was (ADR 0005's amendment), even past the
 *     sweep's reach.
 *  3. For an active task whose latest moment before now falls before the window and has no
 *     stored row at all, one invented pending row at that moment: an unanswered task does not
 *     expire with the lookback, and only a moment with no row needs one.
 *
 * Pure: no clock, no storage. The caller reads the two sets of Instances and supplies `now`.
 */

/**
 * The most moments one Reminder may contribute to the Tasks tab's window. The window is the
 * sweep's seven-day lookback through day +7, so a task on the tightest interval the app can
 * create (one hour) has at most 360 moments; 512 leaves room for a daylight-saving day.
 */
const val TASK_WINDOW_MAX_OCCURRENCES = 512

/** The window the Tasks tab projects: the start of the local day seven back to the end of day +7. */
fun taskWindow(now: Long, zone: ZoneId): TimelineWindow = TimelineWindow(localDayBounds(now, -7, zone).start, localDayBounds(now, 7, zone).end)

data class TasksTabInput(
    /** Every Reminder, of either Kind, active or not: a stopped task's recorded rows still show. */
    val reminders: List<Reminder>,
    /** The Instances inside [taskWindow]. Medication rows are ignored. */
    val instances: List<ReminderInstance>,
    /** The task Instances scheduled before [taskWindow]. Later ones are ignored here. */
    val olderInstances: List<ReminderInstance>,
    val now: Long,
    val zone: ZoneId,
)

/** The Tasks tab's sections, from what is stored and what the Schedules predict. */
fun assembleTasksTab(input: TasksTabInput): TaskSections {
    val window = taskWindow(input.now, input.zone)
    val tasks = input.reminders.filter { it.kind == ReminderKind.Task }
    val byId = tasks.associateBy { it.id }

    val inside =
        input.instances
            .filter { it.reminderKind == ReminderKind.Task }
            .map { instance ->
                val settled = settleExpiredSnooze(instance, input.now)
                if (settled == instance.status) instance else instance.copy(status = settled)
            }

    val projected =
        projectTimeline(
            ProjectTimelineInput(
                reminders = tasks,
                instances = inside,
                window = window,
                now = input.now,
                zone = input.zone,
                maxOccurrencesPerReminder = TASK_WINDOW_MAX_OCCURRENCES,
            ),
        )

    val older =
        input.olderInstances
            .filter { it.reminderKind == ReminderKind.Task && it.scheduledTime < window.start }
            .mapNotNull { instance ->
                val reminder = byId[instance.reminderId] ?: return@mapNotNull null
                val status = settleExpiredSnooze(instance, input.now)
                TimelineRow(
                    reminderId = reminder.id,
                    instanceId = instance.id,
                    name = reminder.name,
                    dosage = reminder.dosage,
                    kind = reminder.kind,
                    scheduledTime = instance.scheduledTime,
                    status = status,
                    snoozedUntil = instance.snoozedUntil,
                    isNext = false,
                    completedAt = instance.completedAt.takeIf { status == InstanceStatus.Completed },
                )
            }

    val olderKeys = older.mapTo(HashSet()) { timelineRowKey(it.reminderId, it.scheduledTime) }
    val invented =
        tasks
            .filter { it.active }
            .mapNotNull { reminder ->
                val latest = latestOccurrenceBefore(reminder, input.now, input.zone) ?: return@mapNotNull null
                if (latest.fireAt < reminder.createdAt || latest.fireAt >= window.start) return@mapNotNull null
                // Any stored row at that moment, answered or not, already says what became of it.
                if (timelineRowKey(reminder.id, latest.fireAt) in olderKeys) return@mapNotNull null
                TimelineRow(
                    reminderId = reminder.id,
                    instanceId = null,
                    name = reminder.name,
                    dosage = reminder.dosage,
                    kind = reminder.kind,
                    scheduledTime = latest.fireAt,
                    status = InstanceStatus.Pending,
                    snoozedUntil = null,
                    isNext = false,
                )
            }

    return sectionTasks(projected + older + invented, input.now, input.zone)
}

/** A snooze nobody came back to reads as the miss it was, both inside and before the window. */
private fun settleExpiredSnooze(instance: ReminderInstance, now: Long): InstanceStatus =
    if (instance.status == InstanceStatus.Snoozed && snoozeRanOut(instance, now, GRACE_PERIOD_MS)) {
        InstanceStatus.Missed
    } else {
        instance.status
    }
