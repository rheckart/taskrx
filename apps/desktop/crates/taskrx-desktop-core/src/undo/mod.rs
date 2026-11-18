//! Taking back an answer: which answers can be, and what happens after (ADR 0008).
//! Kotlin: `core/undo/Undo.kt`.

use std::collections::{BTreeSet, HashMap};

use jiff::tz::TimeZone;

use crate::domain::{InstanceStatus, Reminder, is_undoable};
use crate::schedule::next_occurrences;
use crate::timeline::TimelineRow;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UndoVerdict {
    Allowed,
    /// Pending, or the sweep's Missed: not an answer.
    NotAnAnswer,
    /// A moment the Schedule never predicts: reopened, it would be a miss that never was.
    NotScheduled,
    /// Another Instance at the moment is already open, so this would make two.
    MomentOpenAgain,
}

impl UndoVerdict {
    pub fn wire(self) -> &'static str {
        match self {
            Self::Allowed => "allowed",
            Self::NotAnAnswer => "notAnAnswer",
            Self::NotScheduled => "notScheduled",
            Self::MomentOpenAgain => "momentOpenAgain",
        }
    }
}

/// Whether `scheduled_time` is exactly one of the reminder's Occurrences, moves and pauses applied.
pub fn is_scheduled_occurrence(reminder: &Reminder, scheduled_time: i64, tz: &TimeZone) -> bool {
    next_occurrences(reminder, scheduled_time - 1, 1, tz)
        .ok()
        .and_then(|o| o.first().map(|o| o.fire_at))
        == Some(scheduled_time)
}

pub fn undo_verdict(
    status: InstanceStatus,
    reminder: &Reminder,
    scheduled_time: i64,
    other_open_at_moment: bool,
    tz: &TimeZone,
) -> UndoVerdict {
    if !is_undoable(status) {
        UndoVerdict::NotAnAnswer
    } else if !is_scheduled_occurrence(reminder, scheduled_time, tz) {
        UndoVerdict::NotScheduled
    } else if other_open_at_moment {
        UndoVerdict::MomentOpenAgain
    } else {
        UndoVerdict::Allowed
    }
}

/// Whether the reopened moment is already past its Grace Period, so the next sweep records it Missed.
pub fn undo_will_be_missed(scheduled_time: i64, now: i64, grace_period_ms: i64) -> bool {
    scheduled_time < now - grace_period_ms
}

/// The Instances a screen offers to take back from the row: an answer on a scheduled moment.
/// Read over every row on screen at once (the day and the Tasks tab together).
pub fn undoable_instance_ids<'a>(
    rows: impl IntoIterator<Item = &'a TimelineRow>,
    reminders: &[Reminder],
    tz: &TimeZone,
) -> BTreeSet<String> {
    let by_id: HashMap<&str, &Reminder> = reminders.iter().map(|r| (r.id.as_str(), r)).collect();
    rows.into_iter()
        .filter_map(|row| {
            let id = row.instance_id.as_ref()?;
            let reminder = by_id.get(row.reminder_id.as_str())?;
            (is_undoable(row.status) && is_scheduled_occurrence(reminder, row.scheduled_time, tz))
                .then(|| id.clone())
        })
        .collect()
}
