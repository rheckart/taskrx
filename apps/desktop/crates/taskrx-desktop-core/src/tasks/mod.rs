//! The Tasks tab: Overdue, Due today and Upcoming, and the rows they are made from.
//! Kotlin: `core/tasks/TaskSections.kt`, `TasksTab.kt`.

use std::collections::{BTreeMap, HashMap, HashSet};

use jiff::tz::TimeZone;

use crate::domain::{InstanceStatus, Reminder, ReminderInstance, ReminderKind};
use crate::schedule::latest_occurrence_before;
use crate::sweep::{GRACE_PERIOD_MS, snooze_ran_out};
use crate::time::local_day_bounds;
use crate::timeline::{
    ProjectTimelineInput, TimelineRow, TimelineWindow, project_timeline, timeline_row_key,
};

/// One local day of Upcoming rows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpcomingDay {
    pub day_start: i64,
    pub rows: Vec<TimelineRow>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskSections {
    pub overdue: Vec<TimelineRow>,
    pub due_today: Vec<TimelineRow>,
    pub upcoming: Vec<UpcomingDay>,
}

fn not_next(row: &TimelineRow) -> TimelineRow {
    TimelineRow {
        is_next: false,
        ..row.clone()
    }
}

fn by_time_then_reminder(a: &TimelineRow, b: &TimelineRow) -> std::cmp::Ordering {
    a.scheduled_time
        .cmp(&b.scheduled_time)
        .then_with(|| a.reminder_id.cmp(&b.reminder_id))
}

/// The three sections from the task rows of a wide window. Medication rows are ignored. Overdue
/// is one row per task (its latest past-due pending or missed moment), most overdue first, with
/// no lookback of its own; no row carries NEXT.
pub fn section_tasks(rows: &[TimelineRow], now: i64, tz: &TimeZone) -> TaskSections {
    let tasks: Vec<&TimelineRow> = rows
        .iter()
        .filter(|r| r.kind == ReminderKind::Task)
        .collect();
    let today = local_day_bounds(now, 0, tz);
    let horizon_end = local_day_bounds(now, 7, tz).end;

    // Grouped in first-seen order; within a group the first of equal latest moments wins.
    let mut groups: Vec<(&str, &TimelineRow)> = Vec::new();
    for row in tasks.iter().filter(|r| {
        r.scheduled_time < now
            && matches!(r.status, InstanceStatus::Missed | InstanceStatus::Pending)
    }) {
        match groups.iter_mut().find(|(id, _)| *id == row.reminder_id) {
            Some((_, latest)) => {
                if row.scheduled_time > latest.scheduled_time {
                    *latest = row;
                }
            }
            None => groups.push((&row.reminder_id, row)),
        }
    }
    let mut overdue: Vec<TimelineRow> = groups.iter().map(|(_, r)| not_next(r)).collect();
    overdue.sort_by_key(|r| r.scheduled_time);

    let taken: HashSet<String> = overdue
        .iter()
        .map(|r| timeline_row_key(&r.reminder_id, r.scheduled_time))
        .collect();
    let mut due_today: Vec<TimelineRow> = tasks
        .iter()
        .filter(|r| r.scheduled_time >= today.start && r.scheduled_time <= today.end)
        .filter(|r| !taken.contains(&timeline_row_key(&r.reminder_id, r.scheduled_time)))
        .map(|r| not_next(r))
        .collect();
    due_today.sort_by(by_time_then_reminder);

    let mut days: BTreeMap<i64, Vec<TimelineRow>> = BTreeMap::new();
    for row in tasks
        .iter()
        .filter(|r| r.scheduled_time > today.end && r.scheduled_time <= horizon_end)
    {
        days.entry(local_day_bounds(row.scheduled_time, 0, tz).start)
            .or_default()
            .push(not_next(row));
    }
    let upcoming = days
        .into_iter()
        .map(|(day_start, mut rows)| {
            rows.sort_by(by_time_then_reminder);
            UpcomingDay { day_start, rows }
        })
        .collect();

    TaskSections {
        overdue,
        due_today,
        upcoming,
    }
}

/// The most moments one Reminder may contribute to the Tasks tab's fortnight.
pub const TASK_WINDOW_MAX_OCCURRENCES: i64 = 512;

/// The window the Tasks tab projects: seven local days back through the end of day seven ahead.
pub fn task_window(now: i64, tz: &TimeZone) -> TimelineWindow {
    TimelineWindow {
        start: local_day_bounds(now, -7, tz).start,
        end: local_day_bounds(now, 7, tz).end,
    }
}

pub struct TasksTabInput<'a> {
    /// Every Reminder, of either Kind, active or not.
    pub reminders: &'a [Reminder],
    /// The Instances inside [`task_window`]. Medication rows are ignored.
    pub instances: &'a [ReminderInstance],
    /// The task Instances scheduled before [`task_window`].
    pub older_instances: &'a [ReminderInstance],
    pub now: i64,
    pub zone: &'a TimeZone,
}

/// The Tasks tab's sections: the fortnight's projection, the stored task rows from before it
/// (an old snooze nobody came back to read as missed), and one invented row at the latest
/// moment of an active task that has no row there at all.
pub fn assemble_tasks_tab(input: &TasksTabInput) -> TaskSections {
    let window = task_window(input.now, input.zone);
    let tasks: Vec<Reminder> = input
        .reminders
        .iter()
        .filter(|r| r.kind == ReminderKind::Task)
        .cloned()
        .collect();
    let by_id: HashMap<&str, &Reminder> = tasks.iter().map(|r| (r.id.as_str(), r)).collect();
    let inside: Vec<ReminderInstance> = input
        .instances
        .iter()
        .filter(|i| i.reminder_kind == ReminderKind::Task)
        .cloned()
        .collect();

    let projected = project_timeline(&ProjectTimelineInput {
        reminders: &tasks,
        instances: &inside,
        window,
        now: input.now,
        zone: input.zone,
        max_occurrences_per_reminder: TASK_WINDOW_MAX_OCCURRENCES,
    });

    let older: Vec<TimelineRow> = input
        .older_instances
        .iter()
        .filter(|i| i.reminder_kind == ReminderKind::Task && i.scheduled_time < window.start)
        .filter_map(|instance| {
            let reminder = by_id.get(instance.reminder_id.as_str())?;
            let status = if instance.status == InstanceStatus::Snoozed
                && snooze_ran_out(instance, input.now, GRACE_PERIOD_MS)
            {
                InstanceStatus::Missed
            } else {
                instance.status
            };
            Some(TimelineRow {
                reminder_id: reminder.id.clone(),
                instance_id: Some(instance.id.clone()),
                name: reminder.name.clone(),
                dosage: reminder.dosage.clone(),
                kind: reminder.kind,
                scheduled_time: instance.scheduled_time,
                status,
                snoozed_until: instance.snoozed_until,
                is_next: false,
                completed_at: instance
                    .completed_at
                    .filter(|_| status == InstanceStatus::Completed),
            })
        })
        .collect();

    let older_keys: HashSet<String> = older
        .iter()
        .map(|r| timeline_row_key(&r.reminder_id, r.scheduled_time))
        .collect();
    let invented: Vec<TimelineRow> = tasks
        .iter()
        .filter(|r| r.active)
        .filter_map(|reminder| {
            let latest = latest_occurrence_before(reminder, input.now, input.zone)?;
            if latest.fire_at < reminder.created_at || latest.fire_at >= window.start {
                return None;
            }
            // Any stored row at that moment already says what became of it.
            if older_keys.contains(&timeline_row_key(&reminder.id, latest.fire_at)) {
                return None;
            }
            Some(TimelineRow {
                reminder_id: reminder.id.clone(),
                instance_id: None,
                name: reminder.name.clone(),
                dosage: reminder.dosage.clone(),
                kind: reminder.kind,
                scheduled_time: latest.fire_at,
                status: InstanceStatus::Pending,
                snoozed_until: None,
                is_next: false,
                completed_at: None,
            })
        })
        .collect();

    let all: Vec<TimelineRow> = projected.into_iter().chain(older).chain(invented).collect();
    section_tasks(&all, input.now, input.zone)
}
