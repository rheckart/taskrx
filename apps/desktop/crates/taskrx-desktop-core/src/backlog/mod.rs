//! Completing a Backlog item: one with no schedule, completed whenever it is done (ADR 0002).
//! Kotlin: `core/backlog/Completion.kt`.

use crate::domain::{InstanceStatus, Reminder, ReminderKind, ScheduleTimes};

/// The Instance a Backlog completion mints, at the moment the person acts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BacklogCompletion {
    pub reminder_id: String,
    pub reminder_kind: ReminderKind,
    pub scheduled_time: i64,
    pub status: InstanceStatus,
    pub completed_at: i64,
}

pub fn is_backlog(reminder: &Reminder) -> bool {
    reminder.schedule == ScheduleTimes::None
}

/// A scheduled reminder is refused: its occurrence is what gets completed.
pub fn plan_backlog_completion(
    reminder: &Reminder,
    completed_at: i64,
) -> Result<BacklogCompletion, String> {
    if !is_backlog(reminder) {
        return Err(format!(
            "Reminder {} has a schedule, so it is not in the Backlog.",
            reminder.id
        ));
    }
    Ok(BacklogCompletion {
        reminder_id: reminder.id.clone(),
        reminder_kind: reminder.kind,
        scheduled_time: completed_at,
        status: InstanceStatus::Completed,
        completed_at,
    })
}
