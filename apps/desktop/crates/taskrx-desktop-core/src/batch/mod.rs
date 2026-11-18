//! Several doses due at one moment, answered together. Kotlin: `core/batch/Batch.kt`.

use crate::domain::{ReminderKind, is_answerable};
use crate::timeline::TimelineRow;

pub const MIN_BATCH_SIZE: usize = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatchAction {
    Take,
    Snooze,
    Skip,
}

impl BatchAction {
    pub const ALL: [BatchAction; 3] = [Self::Take, Self::Snooze, Self::Skip];

    pub fn wire(self) -> &'static str {
        match self {
            Self::Take => "take",
            Self::Snooze => "snooze",
            Self::Skip => "skip",
        }
    }

    pub fn from_wire(wire: Option<&str>) -> Option<Self> {
        Self::ALL.into_iter().find(|a| Some(a.wire()) == wire)
    }
}

/// The open medication rows grouped by moment, keeping only moments with at least two, in the
/// order each moment first appears.
pub fn batch_slots(rows: &[TimelineRow]) -> Vec<(i64, Vec<TimelineRow>)> {
    let mut slots: Vec<(i64, Vec<TimelineRow>)> = Vec::new();
    for row in rows
        .iter()
        .filter(|r| r.kind == ReminderKind::Medication && is_answerable(r.status))
    {
        match slots.iter_mut().find(|(t, _)| *t == row.scheduled_time) {
            Some((_, slot)) => slot.push(row.clone()),
            None => slots.push((row.scheduled_time, vec![row.clone()])),
        }
    }
    slots.retain(|(_, slot)| slot.len() >= MIN_BATCH_SIZE);
    slots
}

pub fn batch_notification_id(scheduled_time: i64) -> String {
    format!("batch-{scheduled_time}")
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BatchText {
    pub title: String,
    pub body: String,
}

/// "N doses due", and the names sorted as Kotlin's `sorted()` orders strings (by UTF-16 unit).
pub fn batch_text(names: &[String]) -> BatchText {
    let mut sorted = names.to_vec();
    sorted.sort_by(|a, b| a.encode_utf16().cmp(b.encode_utf16()));
    BatchText {
        title: format!("{} doses due", names.len()),
        body: sorted.join(", "),
    }
}
