package com.taskrx.core.tasks

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sweep.SWEEP_LOOKBACK_MS
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.timelineRowKey
import java.time.ZoneId

/*
 * The Tasks tab's three sections: Overdue, Due today, and Upcoming.
 *
 * The timeline projection answers "what is on this day". The Tasks tab asks a
 * different question — "what still needs me, and what is coming" — so it needs
 * a second, deliberately narrow rule rather than a screen full of filters. This
 * is that rule: given the task rows of a wide window and the current instant, it
 * returns the three lists the tab renders.
 *
 * ## Why Overdue is one row per task
 *
 * A recurring task missed every weekday leaves five `missed` rows, one per day,
 * and rendering all five buries what matters: the task is overdue, and the
 * latest moment is the one the person recognises. The older misses are not
 * thrown away — they stay recorded, reachable in History and answerable in
 * Catch up — they are simply not five copies of the same sentence.
 *
 * ## Why the lookback is `SWEEP_LOOKBACK_MS`
 *
 * "Overdue" and "Catch up" must agree on how far back they reach. Catch up
 * lists what the sweep has recorded as Missed, and the sweep only reaches
 * `SWEEP_LOOKBACK_MS` back, so nothing older was ever recorded to be overdue
 * for. Reading the same constant keeps the two from drifting.
 *
 * ## What this deliberately does not decide
 *
 * It does not mint or settle anything, and it never changes a status. "Overdue"
 * is a word the screen says for a task whose moment has passed — the stored
 * status stays `pending` or `missed`, the sweep is unchanged, and no sync or
 * backend rule is involved (CONTEXT.md: Missed is only ever *shown* as Overdue
 * for a task).
 *
 * Pure: no clock, no database, no globals. The caller supplies `now`.
 */

/** One local day of Upcoming rows, oldest day first. */
data class UpcomingDay(val dayStart: Long, val rows: List<TimelineRow>)

/** The Tasks tab: what is overdue, what is due today, and the week ahead. */
data class TaskSections(
    val overdue: List<TimelineRow>,
    val dueToday: List<TimelineRow>,
    val upcoming: List<UpcomingDay>,
)

/**
 * The three sections for the Tasks tab, from the task rows of a window wide
 * enough to hold them (at least `now - SWEEP_LOOKBACK_MS` through day +7).
 *
 * Medication rows in [rows] are ignored: the Medications tab keeps its single
 * day view, and a task is the only thing these sections are about.
 *
 * Every returned row has `isNext = false`. NEXT belongs to a single day's
 * timeline; with a week on screen it would mark one arbitrary row as "do this
 * now".
 */
fun sectionTasks(
    rows: List<TimelineRow>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): TaskSections {
    val tasks = rows.filter { it.kind == ReminderKind.Task }
    val today = localDayBounds(now, 0, zone)
    val horizonEnd = localDayBounds(now, 7, zone).end
    val floor = now - SWEEP_LOOKBACK_MS

    // Most overdue first: the task whose latest miss is oldest sits at the top.
    // One row per task — the latest moment it was missed or fell due — so a
    // recurring task is not five near-identical rows.
    val overdue =
        tasks
            .filter {
                it.scheduledTime < now &&
                    it.scheduledTime >= floor &&
                    (it.status == InstanceStatus.Missed || it.status == InstanceStatus.Pending)
            }
            .groupBy { it.reminderId }
            .map { (_, missed) -> missed.maxBy { it.scheduledTime } }
            .sortedBy { it.scheduledTime }
            .map { it.copy(isNext = false) }

    // The rows Overdue has taken, so a past-due moment is never in two places at once.
    val overdueKeys = overdue.mapTo(HashSet()) { timelineRowKey(it.reminderId, it.scheduledTime) }

    val dueToday =
        tasks
            .filter { it.scheduledTime in today.start..today.end }
            .filter { timelineRowKey(it.reminderId, it.scheduledTime) !in overdueKeys }
            .sortedWith(compareBy({ it.scheduledTime }, { it.reminderId }))
            .map { it.copy(isNext = false) }

    val upcoming =
        tasks
            .filter { it.scheduledTime > today.end && it.scheduledTime <= horizonEnd }
            .groupBy { localDayBounds(it.scheduledTime, 0, zone).start }
            .toSortedMap()
            .map { (dayStart, dayRows) ->
                UpcomingDay(
                    dayStart = dayStart,
                    rows = dayRows.sortedWith(compareBy({ it.scheduledTime }, { it.reminderId })).map { it.copy(isNext = false) },
                )
            }

    return TaskSections(overdue = overdue, dueToday = dueToday, upcoming = upcoming)
}
