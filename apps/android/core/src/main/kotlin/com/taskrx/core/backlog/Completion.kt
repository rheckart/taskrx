package com.taskrx.core.backlog

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes

/*
 * The Backlog completion rule: what Instance should exist when someone acts on
 * a Reminder that has no Schedule.
 *
 * A Backlog Reminder produces no Occurrences, so there is no due-time to key its
 * Reminder Instance by — and yet completing one has to record something. ADR
 * 0002 settled that: the Instance is minted at the moment the person acts, with
 * `scheduledTime` equal to the completion time, so `scheduledTime` means "the
 * instant this Instance occupied on the timeline" for both halves of the domain.
 * It stays non-null, which is what leaves the four time-ordered indexes over it
 * valid.
 *
 * The decision is here rather than in the screen that renders the Backlog for
 * the reason `docs/specs/fused-timeline.md` gives: the on-device repositories
 * persist what the pure functions decide, and this is the only place the rule is
 * written down.
 *
 * Pure: no clock, no ids, no database. The caller supplies the instant and
 * generates the id.
 */

/**
 * The Instance a completed Backlog Reminder should produce.
 *
 * Deliberately not a whole `ReminderInstance`: identity and the bookkeeping
 * timestamps belong to whoever writes the row, and inventing them here would
 * make this function impure for no gain.
 */
data class BacklogCompletion(
    val reminderId: String,
    val reminderKind: ReminderKind,
    /** Unix ms; equal to [completedAt] (ADR 0002). */
    val scheduledTime: Long,
    val status: InstanceStatus,
    /** Unix ms. */
    val completedAt: Long,
)

/**
 * Whether [reminder] belongs to the Backlog.
 *
 * Only the `none` variant counts. A `daily` schedule with an empty times list
 * also produces no Occurrences, but it is a misconfiguration rather than a
 * commitment with no moment attached — telling the two apart in the type is what
 * [NoneTimes] exists for, and treating them alike here would put the accident
 * back.
 */
fun isBacklog(reminder: Reminder): Boolean = reminder.schedule is NoneTimes

/**
 * The Instance to mint when a Backlog Reminder is completed at [completedAt]
 * (Unix ms).
 *
 * Throws [IllegalArgumentException] for a Reminder that has a Schedule. Such a
 * Reminder's moments are predicted, and an Instance minted at "now" instead
 * would sit at a moment no Occurrence claims: it would dodge the
 * Reminder-and-scheduled-time pairing the timeline and the sweep both
 * deduplicate on, appear as a second row beside the moment it was meant to
 * answer, and be counted twice by Adherence. A throw rather than a silent
 * correction, because there is no sensible moment to substitute — only the
 * caller knows which Occurrence it meant.
 */
fun planBacklogCompletion(reminder: Reminder, completedAt: Long): BacklogCompletion {
    require(isBacklog(reminder)) {
        val type = scheduleTypeName(reminder.schedule)
        "Reminder ${reminder.id} has a $type schedule, so it is not in the Backlog; " +
            "complete the occurrence it came due at instead."
    }

    return BacklogCompletion(
        reminderId = reminder.id,
        reminderKind = reminder.kind,
        scheduledTime = completedAt,
        status = InstanceStatus.Completed,
        completedAt = completedAt,
    )
}

/** The wire spelling of a schedule's variant, as TS `schedule.type`; not derived from class names, which minification renames. */
internal fun scheduleTypeName(schedule: ScheduleTimes): String =
    when (schedule) {
        is DailyTimes -> "daily"
        is IntervalTimes -> "interval"
        is WeeklyTimes -> "weekly"
        is OnceTimes -> "once"
        NoneTimes -> "none"
    }
