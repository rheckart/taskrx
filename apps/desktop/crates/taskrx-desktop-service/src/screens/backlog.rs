//! The Backlog: active commitments with no moment (ADR 0002). Mirrors the phone's
//! `BacklogViewModel`: both Kinds, stopped ones left out, each with when it was last done.

use taskrx_desktop_core::backlog::is_backlog;
use taskrx_desktop_core::domain::{InstanceStatus, Reminder};

use crate::data::Data;

#[derive(Debug, Clone, PartialEq)]
pub struct BacklogEntry {
    pub reminder: Reminder,
    /// When it was last completed, if ever.
    pub last_done: Option<i64>,
}

pub fn backlog(data: &Data) -> Vec<BacklogEntry> {
    data.reminders
        .iter()
        .filter(|r| r.active && is_backlog(r))
        .map(|r| BacklogEntry {
            reminder: r.clone(),
            last_done: data
                .instances
                .iter()
                .filter(|i| i.reminder_id == r.id && i.status == InstanceStatus::Completed)
                .filter_map(|i| i.completed_at)
                .max(),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data::Lists;
    use serde_json::json;

    #[test]
    fn active_unscheduled_reminders_with_their_last_completion() {
        let reminder = |id: &str, schedule: serde_json::Value, active: bool| {
            json!({
                "_id": format!("srv_{id}"), "localId": id, "userId": "u", "kind": "task",
                "name": id, "schedule": schedule, "priority": "normal", "active": active,
                "createdAt": 1.0, "updatedAt": 1.0
            })
        };
        let data = Data::decode(&Lists {
            reminders: Some(json!([
                reminder("loose", json!({ "type": "none" }), true),
                reminder("stopped", json!({ "type": "none" }), false),
                reminder(
                    "daily",
                    json!({ "type": "daily", "times": ["09:00"] }),
                    true
                ),
            ])),
            instances: Some(json!([
                { "_id": "srv_i1", "localId": "i1", "userId": "u", "reminderId": "srv_loose",
                  "reminderKind": "task", "scheduledTime": 5.0, "status": "completed",
                  "completedAt": 5.0, "snoozeCount": 0.0, "createdAt": 5.0, "updatedAt": 5.0 },
                { "_id": "srv_i2", "localId": "i2", "userId": "u", "reminderId": "srv_loose",
                  "reminderKind": "task", "scheduledTime": 9.0, "status": "completed",
                  "completedAt": 9.0, "snoozeCount": 0.0, "createdAt": 9.0, "updatedAt": 9.0 }
            ])),
            ..Lists::default()
        })
        .unwrap();
        let entries = backlog(&data);
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].reminder.id, "loose");
        assert_eq!(entries[0].last_done, Some(9));
    }
}
