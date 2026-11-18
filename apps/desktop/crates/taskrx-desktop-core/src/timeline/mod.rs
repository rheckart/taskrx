//! The timeline projection: a person's day as one ordered list. Kotlin:
//! `core/timeline/Projection.kt`.
//!
//! A day is assembled from the moments the Schedules predict and the Instances already
//! recorded, deduplicated on the pairing of Reminder and scheduled time; where both exist the
//! Instance wins. It does not mint Instances or decide that a moment was missed (the sweep's
//! job), and it is Kind-neutral. Pure: the caller supplies `now`.

use std::collections::HashMap;

use jiff::tz::TimeZone;

use crate::domain::{InstanceStatus, Reminder, ReminderInstance, ReminderKind};
use crate::schedule::next_occurrences;

/// The span the timeline covers, both bounds inclusive, in Unix ms.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimelineWindow {
    pub start: i64,
    pub end: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TimelineRow {
    pub reminder_id: String,
    /// `None` until the moment has been answered and an Instance exists.
    pub instance_id: Option<String>,
    pub name: String,
    pub dosage: Option<String>,
    pub kind: ReminderKind,
    pub scheduled_time: i64,
    /// `Pending` for an Occurrence with no Instance behind it.
    pub status: InstanceStatus,
    /// Where a snoozed Instance was deferred to. Carried, not acted on: the row stays put.
    pub snoozed_until: Option<i64>,
    /// The one row to act on now: the soonest unanswered moment at or after `now`.
    pub is_next: bool,
    /// When a completed Instance was recorded as done.
    pub completed_at: Option<i64>,
}

/// The most moments one Reminder may contribute to a one-day window.
pub const DEFAULT_MAX_OCCURRENCES_PER_REMINDER: i64 = 64;

pub struct ProjectTimelineInput<'a> {
    /// Every Reminder that could contribute. Only active ones predict new moments; an inactive
    /// one still names the moments already recorded against it.
    pub reminders: &'a [Reminder],
    pub instances: &'a [ReminderInstance],
    pub window: TimelineWindow,
    pub now: i64,
    pub zone: &'a TimeZone,
    pub max_occurrences_per_reminder: i64,
}

/// Reminder and scheduled time together: the deduplication key.
pub fn timeline_row_key(reminder_id: &str, scheduled_time: i64) -> String {
    format!("{reminder_id}@{scheduled_time}")
}

fn within(moment: i64, window: TimelineWindow) -> bool {
    moment >= window.start && moment <= window.end
}

/// Which of two Instances claiming one pairing to show: an answer over pending, then the later
/// `updated_at`, then the smaller id, so identical data cannot flicker.
fn preferred<'a>(a: &'a ReminderInstance, b: &'a ReminderInstance) -> &'a ReminderInstance {
    let (a_answered, b_answered) = (
        a.status != InstanceStatus::Pending,
        b.status != InstanceStatus::Pending,
    );
    if a_answered != b_answered {
        return if a_answered { a } else { b };
    }
    if a.updated_at != b.updated_at {
        return if a.updated_at > b.updated_at { a } else { b };
    }
    if a.id < b.id { a } else { b }
}

pub fn project_timeline(input: &ProjectTimelineInput) -> Vec<TimelineRow> {
    let by_id: HashMap<&str, &Reminder> =
        input.reminders.iter().map(|r| (r.id.as_str(), r)).collect();

    // Stored answers first, so they own their moment. First-seen order, as a LinkedHashMap keeps it.
    let mut order: Vec<String> = Vec::new();
    let mut claimed: HashMap<String, (&ReminderInstance, &Reminder)> = HashMap::new();
    for instance in input.instances {
        if !within(instance.scheduled_time, input.window) {
            continue;
        }
        let Some(reminder) = by_id.get(instance.reminder_id.as_str()) else {
            continue;
        };
        let key = timeline_row_key(&instance.reminder_id, instance.scheduled_time);
        let chosen = match claimed.get(&key) {
            None => {
                order.push(key.clone());
                instance
            }
            Some((held, _)) => preferred(held, instance),
        };
        claimed.insert(key, (chosen, reminder));
    }

    let mut rows: Vec<TimelineRow> = order
        .iter()
        .map(|key| {
            let (instance, reminder) = claimed[key];
            TimelineRow {
                reminder_id: reminder.id.clone(),
                instance_id: Some(instance.id.clone()),
                name: reminder.name.clone(),
                dosage: reminder.dosage.clone(),
                kind: reminder.kind,
                scheduled_time: instance.scheduled_time,
                status: instance.status,
                snoozed_until: instance.snoozed_until,
                is_next: false,
                completed_at: instance
                    .completed_at
                    .filter(|_| instance.status == InstanceStatus::Completed),
            }
        })
        .collect();

    for reminder in input.reminders.iter().filter(|r| r.active) {
        // The anchor sits a millisecond before the window: the engine excludes its own `from`.
        let occurrences = next_occurrences(
            reminder,
            input.window.start - 1,
            input.max_occurrences_per_reminder,
            input.zone,
        )
        .unwrap_or_default();
        for occurrence in occurrences {
            if occurrence.fire_at > input.window.end {
                break;
            }
            if claimed.contains_key(&timeline_row_key(&reminder.id, occurrence.fire_at)) {
                continue;
            }
            rows.push(TimelineRow {
                reminder_id: reminder.id.clone(),
                instance_id: None,
                name: reminder.name.clone(),
                dosage: reminder.dosage.clone(),
                kind: reminder.kind,
                scheduled_time: occurrence.fire_at,
                status: InstanceStatus::Pending,
                snoozed_until: None,
                is_next: false,
                completed_at: None,
            });
        }
    }

    // Chronological, ties broken on the reminder; stable otherwise.
    rows.sort_by(|a, b| {
        a.scheduled_time
            .cmp(&b.scheduled_time)
            .then_with(|| a.reminder_id.cmp(&b.reminder_id))
    });
    if let Some(next) = rows
        .iter()
        .position(|r| r.status == InstanceStatus::Pending && r.scheduled_time >= input.now)
    {
        rows[next].is_next = true;
    }
    rows
}
