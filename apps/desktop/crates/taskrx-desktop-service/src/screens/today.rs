//! Today: the day's medications and the Tasks tab. Mirrors the phone's `TodayViewModel.load`.
//!
//! One difference, by ADR 0016: the phone runs the missed sweep and records its verdicts, and the
//! desktop never records one. It runs the same planner and shows a past-grace moment as Missed,
//! while the row underneath stays what the server holds, so answering it is still allowed.

use std::collections::{BTreeSet, HashSet};

use jiff::tz::TimeZone;
use taskrx_desktop_core::asneeded::{AsNeededStatus, as_needed_of, as_needed_status, takings};
use taskrx_desktop_core::domain::{
    InstanceStatus, Reminder, ReminderKind, ScheduleTimes, is_answerable,
};
use taskrx_desktop_core::sweep::{
    GRACE_PERIOD_MS, PlanMissedSweepInput, SWEEP_LOOKBACK_MS, plan_missed_sweep,
};
use taskrx_desktop_core::tasks::{TasksTabInput, assemble_tasks_tab, task_window};
use taskrx_desktop_core::time::local_day_bounds;
use taskrx_desktop_core::timeline::{
    DEFAULT_MAX_OCCURRENCES_PER_REMINDER, ProjectTimelineInput, TimelineRow, TimelineWindow,
    project_timeline, timeline_row_key,
};
use taskrx_desktop_core::undo::undoable_instance_ids;

use crate::data::Data;

const DAY_MS: i64 = 86_400_000;

/// One row as drawn.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Row {
    pub row: TimelineRow,
    /// What the row reads as: Missed for a moment past its grace with no answer, else its status.
    pub shown: InstanceStatus,
    /// The answer on it can be taken back from the row.
    pub undoable: bool,
}

impl Row {
    pub fn is_answerable(&self) -> bool {
        is_answerable(self.row.status)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TaskDay {
    pub day_start: i64,
    pub rows: Vec<Row>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AsNeeded {
    pub reminder_id: String,
    pub name: String,
    pub dosage: Option<String>,
    pub status: AsNeededStatus,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Today {
    /// 0 is today, -1 yesterday. Medications never look ahead of today.
    pub day_offset: i32,
    /// Local midnight of the day shown.
    pub day_start: i64,
    /// The day's medication rows, in order.
    pub medications: Vec<Row>,
    pub overdue: Vec<Row>,
    pub due_today: Vec<Row>,
    pub upcoming: Vec<TaskDay>,
    /// Active as-needed medications, by name.
    pub as_needed: Vec<AsNeeded>,
    /// The tab to open on when the person has not picked one: Tasks for someone with no
    /// medication to take.
    pub opens_on: ReminderKind,
    pub open_notices: usize,
}

impl Today {
    /// Rows still wanting an answer, for each tab's badge: the day's medications, and the Tasks
    /// tab's Overdue with today's answerable rows.
    pub fn to_do(&self, kind: ReminderKind) -> usize {
        match kind {
            ReminderKind::Medication => self
                .medications
                .iter()
                .filter(|r| r.is_answerable())
                .count(),
            ReminderKind::Task => {
                self.overdue.len() + self.due_today.iter().filter(|r| r.is_answerable()).count()
            }
        }
    }
}

pub struct TodayInput<'a> {
    pub data: &'a Data,
    pub now: i64,
    pub zone: &'a TimeZone,
    pub day_offset: i32,
}

pub fn today(input: &TodayInput) -> Today {
    let TodayInput {
        data, now, zone, ..
    } = *input;
    let offset = input.day_offset.min(0);
    let day = local_day_bounds(now, offset, zone);
    let reminders = &data.reminders;

    let projected = project_timeline(&ProjectTimelineInput {
        reminders,
        instances: &data.instances,
        window: TimelineWindow {
            start: day.start,
            end: day.end,
        },
        now,
        zone,
        max_occurrences_per_reminder: DEFAULT_MAX_OCCURRENCES_PER_REMINDER,
    });

    // The phone's two reads: the Instances inside the fortnight, and the task rows before it.
    let window = task_window(now, zone);
    let inside: Vec<_> = data
        .instances
        .iter()
        .filter(|i| i.scheduled_time >= window.start && i.scheduled_time <= window.end)
        .cloned()
        .collect();
    let older: Vec<_> = data
        .instances
        .iter()
        .filter(|i| i.reminder_kind == ReminderKind::Task && i.scheduled_time < window.start)
        .cloned()
        .collect();
    let sections = assemble_tasks_tab(&TasksTabInput {
        reminders,
        instances: &inside,
        older_instances: &older,
        now,
        zone,
    });

    let undoable = undoable_instance_ids(
        projected
            .iter()
            .chain(&sections.overdue)
            .chain(&sections.due_today)
            .chain(sections.upcoming.iter().flat_map(|d| &d.rows)),
        reminders,
        zone,
    );
    let missed = shown_missed(data, now, zone);
    let row = |r: &TimelineRow| draw(r, &missed, &undoable);

    let medications: Vec<Row> = projected
        .iter()
        .filter(|r| r.kind == ReminderKind::Medication)
        .map(row)
        .collect();
    let no_medications = !reminders
        .iter()
        .any(|r| r.kind == ReminderKind::Medication && r.active)
        && !medications.iter().any(Row::is_answerable);

    Today {
        day_offset: offset,
        day_start: day.start,
        overdue: sections.overdue.iter().map(row).collect(),
        due_today: sections.due_today.iter().map(row).collect(),
        upcoming: sections
            .upcoming
            .iter()
            .map(|d| TaskDay {
                day_start: d.day_start,
                rows: d.rows.iter().map(row).collect(),
            })
            .collect(),
        medications,
        as_needed: as_needed(reminders, data, now),
        opens_on: if no_medications {
            ReminderKind::Task
        } else {
            ReminderKind::Medication
        },
        open_notices: data.open_notices(),
    }
}

fn draw(r: &TimelineRow, missed: &HashSet<String>, undoable: &BTreeSet<String>) -> Row {
    let past_grace = missed.contains(&timeline_row_key(&r.reminder_id, r.scheduled_time));
    Row {
        shown: if past_grace && is_answerable(r.status) {
            InstanceStatus::Missed
        } else {
            r.status
        },
        undoable: r
            .instance_id
            .as_ref()
            .is_some_and(|id| undoable.contains(id)),
        row: r.clone(),
    }
}

/// The moments the phone's sweep would record as Missed, read and never written.
pub(crate) fn shown_missed(data: &Data, now: i64, zone: &TimeZone) -> HashSet<String> {
    let recent: Vec<_> = data
        .instances
        .iter()
        .filter(|i| i.scheduled_time >= now - SWEEP_LOOKBACK_MS && i.scheduled_time <= now)
        .cloned()
        .collect();
    plan_missed_sweep(&PlanMissedSweepInput {
        reminders: &data.reminders,
        instances: &recent,
        now,
        grace_period_ms: GRACE_PERIOD_MS,
        lookback_ms: SWEEP_LOOKBACK_MS,
        zone,
    })
    .into_iter()
    .map(|v| timeline_row_key(&v.reminder_id, v.scheduled_time))
    .collect()
}

fn as_needed(reminders: &[Reminder], data: &Data, now: i64) -> Vec<AsNeeded> {
    // Two days back by the Instance's own moment, as on the phone: room for a time moved later.
    let recent: Vec<_> = data
        .instances
        .iter()
        .filter(|i| i.scheduled_time >= now - 2 * DAY_MS)
        .cloned()
        .collect();
    let mut list: Vec<AsNeeded> = reminders
        .iter()
        .filter(|r| r.active && r.kind == ReminderKind::Medication)
        .filter(|r| matches!(r.schedule, ScheduleTimes::AsNeeded { .. }))
        .filter_map(|r| {
            let (gap, max) = as_needed_of(r)?;
            Some(AsNeeded {
                reminder_id: r.id.clone(),
                name: r.name.clone(),
                dosage: r.dosage.clone(),
                status: as_needed_status(gap, max, &takings(&recent, &r.id), now),
            })
        })
        .collect();
    list.sort_by_cached_key(|a| a.name.to_lowercase());
    list
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data::Lists;
    use serde_json::{Value, json};

    fn at(local: &str) -> i64 {
        format!("{local}[America/New_York]")
            .parse::<jiff::Zoned>()
            .unwrap()
            .timestamp()
            .as_millisecond()
    }

    fn reminder(local_id: &str, kind: &str, name: &str, schedule: Value) -> Value {
        json!({
            "_id": format!("srv_{local_id}"), "localId": local_id, "userId": "user_1",
            "kind": kind, "name": name, "schedule": schedule, "priority": "normal",
            "active": true, "createdAt": at("2026-11-01T00:00") as f64, "updatedAt": 1.0
        })
    }

    fn instance(
        local_id: &str,
        parent: &str,
        scheduled: i64,
        status: &str,
        completed: Option<i64>,
    ) -> Value {
        json!({
            "_id": format!("srv_{local_id}"), "localId": local_id, "userId": "user_1",
            "reminderId": format!("srv_{parent}"), "reminderKind": "medication",
            "scheduledTime": scheduled as f64, "status": status,
            "completedAt": completed.map(|c| c as f64), "snoozeCount": 0.0,
            "createdAt": 1.0, "updatedAt": 2.0
        })
    }

    fn data() -> Data {
        Data::decode(&Lists {
            reminders: Some(json!([
                reminder(
                    "r1",
                    "medication",
                    "Flonase",
                    json!({ "type": "daily", "times": ["09:00", "21:00"] })
                ),
                reminder(
                    "r2",
                    "medication",
                    "advil",
                    json!({ "type": "asNeeded", "minGapMinutes": 240 })
                ),
                reminder(
                    "r3",
                    "medication",
                    "Zinc",
                    json!({ "type": "daily", "times": ["13:00"] })
                ),
            ])),
            instances: Some(json!([
                instance(
                    "i1",
                    "r3",
                    at("2026-11-18T13:00"),
                    "completed",
                    Some(at("2026-11-18T13:05"))
                ),
                instance(
                    "i2",
                    "r2",
                    at("2026-11-18T20:00"),
                    "completed",
                    Some(at("2026-11-18T20:00"))
                ),
            ])),
            ..Lists::default()
        })
        .unwrap()
    }

    fn view(now: i64, day_offset: i32) -> Today {
        let zone = TimeZone::get("America/New_York").unwrap();
        today(&TodayInput {
            data: &data(),
            now,
            zone: &zone,
            day_offset,
        })
    }

    #[test]
    fn the_day_reads_past_grace_as_missed_without_changing_the_row() {
        let t = view(at("2026-11-18T22:00"), 0);
        let rows: Vec<_> = t
            .medications
            .iter()
            .map(|r| (r.row.name.as_str(), r.row.status, r.shown, r.undoable))
            .collect();
        assert_eq!(
            rows,
            vec![
                (
                    "Flonase",
                    InstanceStatus::Pending,
                    InstanceStatus::Missed,
                    false
                ),
                (
                    "Zinc",
                    InstanceStatus::Completed,
                    InstanceStatus::Completed,
                    true
                ),
                // A taking of an as-needed medication is a recorded row of the day, as on the
                // phone, and not one to take back from the row: it was never a scheduled moment.
                (
                    "advil",
                    InstanceStatus::Completed,
                    InstanceStatus::Completed,
                    false
                ),
                (
                    "Flonase",
                    InstanceStatus::Pending,
                    InstanceStatus::Pending,
                    false
                ),
            ]
        );
        assert_eq!(t.to_do(ReminderKind::Medication), 2);
        assert_eq!(t.opens_on, ReminderKind::Medication);
    }

    #[test]
    fn the_next_row_is_the_soonest_unanswered_ahead() {
        let t = view(at("2026-11-18T12:00"), 0);
        let next: Vec<_> = t
            .medications
            .iter()
            .filter(|r| r.row.is_next)
            .map(|r| (r.row.name.as_str(), r.row.scheduled_time))
            .collect();
        // Zinc at 13:00 is already answered, so the next is the evening Flonase.
        assert_eq!(next, vec![("Flonase", at("2026-11-18T21:00"))]);
    }

    #[test]
    fn as_needed_counts_from_the_last_taking() {
        let t = view(at("2026-11-18T22:00"), 0);
        assert_eq!(t.as_needed.len(), 1);
        assert_eq!(t.as_needed[0].name, "advil");
        assert_eq!(t.as_needed[0].status.ready_at, Some(at("2026-11-19T00:00")));
    }

    #[test]
    fn medications_never_look_ahead_of_today() {
        let t = view(at("2026-11-18T22:00"), 2);
        assert_eq!(t.day_offset, 0);
        let yesterday = view(at("2026-11-18T22:00"), -1);
        assert_eq!(yesterday.day_start, at("2026-11-17T00:00"));
        assert!(
            yesterday
                .medications
                .iter()
                .all(|r| r.shown == InstanceStatus::Missed
                    || r.row.status != InstanceStatus::Pending)
        );
    }
}
