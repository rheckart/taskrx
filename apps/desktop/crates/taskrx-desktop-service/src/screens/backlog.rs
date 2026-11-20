//! The Backlog: active commitments with no moment (ADR 0002). Mirrors the phone's
//! `BacklogViewModel`: both Kinds, stopped ones left out, each with when it was last done.
//!
//! Two things the phone does not have yet, because a press here records at once and the item
//! stays put: a record made in the last few minutes is said on the row with a way to take it
//! back, and recording it again asks first (see [`RECORD_AGAIN_MS`]). Today's as-needed Take
//! now works the same way, from the same [`last_record`].

use taskrx_desktop_core::backlog::is_backlog;
use taskrx_desktop_core::domain::{InstanceStatus, Reminder};

use crate::data::Data;

/// How long a record stays "just recorded" on its row: Undo is offered, and recording the same
/// item again takes a second press. Three presses seconds apart once made three doses. The
/// Backlog's Taken or Done and Today's as-needed Take now both use it.
pub const RECORD_AGAIN_MS: i64 = 5 * 60_000;

#[derive(Debug, Clone, PartialEq)]
pub struct BacklogEntry {
    pub reminder: Reminder,
    /// When it was last completed, if ever.
    pub last_done: Option<i64>,
    /// The Instance that completion is.
    pub last_instance: Option<String>,
}

impl BacklogEntry {
    /// The last record, while it is recent enough to take back: its Instance and when.
    pub fn just_recorded(&self, now: i64) -> Option<(&str, i64)> {
        let at = self.last_done?;
        let id = self.last_instance.as_deref()?;
        (now - at < RECORD_AGAIN_MS).then_some((id, at))
    }
}

/// A reminder's latest completed Instance, from any device: when, and which. Ties go to the
/// larger id, so the time shown and the record Undo takes back are always the same one.
pub fn last_record(data: &Data, reminder_id: &str) -> Option<(i64, String)> {
    data.instances
        .iter()
        .filter(|i| i.reminder_id == reminder_id && i.status == InstanceStatus::Completed)
        .filter_map(|i| i.completed_at.map(|at| (at, i.id.clone())))
        .max()
}

/// [`last_record`] while it is recent enough to take back: its Instance and when.
pub fn just_recorded(data: &Data, reminder_id: &str, now: i64) -> Option<(String, i64)> {
    let (at, id) = last_record(data, reminder_id)?;
    (now - at < RECORD_AGAIN_MS).then_some((id, at))
}

pub fn backlog(data: &Data) -> Vec<BacklogEntry> {
    data.reminders
        .iter()
        .filter(|r| r.active && is_backlog(r))
        .map(|r| {
            let last = last_record(data, &r.id);
            BacklogEntry {
                reminder: r.clone(),
                last_done: last.as_ref().map(|(at, _)| *at),
                last_instance: last.map(|(_, id)| id),
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data::Lists;
    use serde_json::json;

    fn reminder(
        id: &str,
        kind: &str,
        schedule: serde_json::Value,
        active: bool,
    ) -> serde_json::Value {
        json!({
            "_id": format!("srv_{id}"), "localId": id, "userId": "u", "kind": kind,
            "name": id, "schedule": schedule, "priority": "normal", "active": active,
            "createdAt": 1.0, "updatedAt": 1.0
        })
    }

    fn done(id: &str, parent: &str, at: f64) -> serde_json::Value {
        json!({ "_id": format!("srv_{id}"), "localId": id, "userId": "u",
                "reminderId": format!("srv_{parent}"), "reminderKind": "task",
                "scheduledTime": at, "status": "completed", "completedAt": at,
                "snoozeCount": 0.0, "createdAt": at, "updatedAt": at })
    }

    #[test]
    fn active_unscheduled_reminders_with_their_last_completion() {
        let data = Data::decode(&Lists {
            reminders: Some(json!([
                reminder("loose", "task", json!({ "type": "none" }), true),
                reminder("stopped", "task", json!({ "type": "none" }), false),
                reminder(
                    "daily",
                    "task",
                    json!({ "type": "daily", "times": ["09:00"] }),
                    true
                ),
            ])),
            instances: Some(json!([done("i1", "loose", 5.0), done("i2", "loose", 9.0)])),
            ..Lists::default()
        })
        .unwrap();
        let entries = backlog(&data);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].reminder.id, "loose");
        assert_eq!(entries[0].last_done, Some(9));
        assert_eq!(entries[0].last_instance.as_deref(), Some("i2"));
    }

    #[test]
    fn a_record_is_just_recorded_for_five_minutes() {
        let data = Data::decode(&Lists {
            reminders: Some(json!([reminder(
                "loose",
                "task",
                json!({ "type": "none" }),
                true
            )])),
            instances: Some(json!([done("i1", "loose", 1_000_000.0)])),
            ..Lists::default()
        })
        .unwrap();
        let entry = &backlog(&data)[0];
        assert_eq!(
            entry.just_recorded(1_000_000 + 60_000),
            Some(("i1", 1_000_000))
        );
        assert_eq!(entry.just_recorded(1_000_000 + RECORD_AGAIN_MS), None);
    }
}
