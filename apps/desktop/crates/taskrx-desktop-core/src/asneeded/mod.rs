//! A medication taken when needed: when the next dose is allowed (ADR 0010).
//! Kotlin: `core/asneeded/AsNeeded.kt`.

use crate::domain::{InstanceStatus, Reminder, ReminderInstance, ScheduleTimes};

const MINUTE_MS: i64 = 60_000;
const DAY_MS: i64 = 24 * 60 * MINUTE_MS;
/// How long a dose that has just become allowed stays on Today.
pub const AS_NEEDED_WINDOW_MS: i64 = 60 * MINUTE_MS;

/// What holds the next dose back.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AsNeededWait {
    Gap,
    Limit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct AsNeededStatus {
    pub last_taken_at: Option<i64>,
    pub taken_in_24h: i32,
    pub ready_at: Option<i64>,
    pub wait: Option<AsNeededWait>,
    /// When the next dose last became allowed, if it has.
    pub opened_at: Option<i64>,
}

impl AsNeededStatus {
    pub fn is_ready(&self, now: i64) -> bool {
        self.ready_at.is_none_or(|r| now >= r)
    }
}

/// The schedule's two limits read together: the gap since the last dose, and at the 24-hour
/// limit, the moment enough of the window's doses have left it.
fn wait_at(
    min_gap_minutes: i32,
    max_per_24h: Option<i32>,
    takings: &[i64],
    now: i64,
    last: i64,
) -> (i64, AsNeededWait) {
    let mut in_window: Vec<i64> = takings
        .iter()
        .copied()
        .filter(|t| *t > now - DAY_MS)
        .collect();
    in_window.sort_unstable();
    let gap_ready = last + i64::from(min_gap_minutes) * MINUTE_MS;
    let limit_ready = max_per_24h
        .filter(|max| in_window.len() as i64 >= i64::from(*max))
        .map(|max| in_window[in_window.len() - max as usize] + DAY_MS);
    match limit_ready {
        Some(limit) if limit > gap_ready => (limit, AsNeededWait::Limit),
        _ => (gap_ready, AsNeededWait::Gap),
    }
}

pub fn as_needed_status(
    min_gap_minutes: i32,
    max_per_24h: Option<i32>,
    takings: &[i64],
    now: i64,
) -> AsNeededStatus {
    let Some(&last) = takings.iter().max() else {
        return AsNeededStatus {
            last_taken_at: None,
            taken_in_24h: 0,
            ready_at: None,
            wait: None,
            opened_at: None,
        };
    };
    let taken_in_24h = takings.iter().filter(|t| **t > now - DAY_MS).count() as i32;
    let (ready_at, wait) = wait_at(min_gap_minutes, max_per_24h, takings, now, last);
    AsNeededStatus {
        last_taken_at: Some(last),
        taken_in_24h,
        ready_at: Some(ready_at),
        wait: Some(wait),
        opened_at: last_opened_at(min_gap_minutes, max_per_24h, takings, now, last),
    }
}

/// The latest instant at or before `now` when the next dose went from held back to allowed.
fn last_opened_at(
    min_gap_minutes: i32,
    max_per_24h: Option<i32>,
    takings: &[i64],
    now: i64,
    last: i64,
) -> Option<i64> {
    let ready = |at: i64| at >= wait_at(min_gap_minutes, max_per_24h, takings, at, last).0;
    let gap = i64::from(min_gap_minutes) * MINUTE_MS;
    let mut candidates: Vec<i64> = Vec::new();
    for t in takings {
        for c in [t + gap, t + DAY_MS] {
            if !candidates.contains(&c) {
                candidates.push(c);
            }
        }
    }
    candidates.retain(|c| *c <= now);
    candidates.sort_unstable_by(|a, b| b.cmp(a));
    candidates.into_iter().find(|c| ready(*c) && !ready(c - 1))
}

/// Whether a dose that has become allowed is still fresh enough to show on Today.
pub fn shows_on_today(status: &AsNeededStatus, now: i64) -> bool {
    let Some(opened) = status.opened_at else {
        return false;
    };
    status.is_ready(now) && now < opened + AS_NEEDED_WINDOW_MS
}

/// When `reminder_id` was taken: the completion times of its completed Instances, in their order.
pub fn takings(instances: &[ReminderInstance], reminder_id: &str) -> Vec<i64> {
    instances
        .iter()
        .filter(|i| i.reminder_id == reminder_id && i.status == InstanceStatus::Completed)
        .filter_map(|i| i.completed_at)
        .collect()
}

/// The as-needed schedule, if the reminder has one: `(min_gap_minutes, max_per_24h)`.
pub fn as_needed_of(reminder: &Reminder) -> Option<(i32, Option<i32>)> {
    match reminder.schedule {
        ScheduleTimes::AsNeeded {
            min_gap_minutes,
            max_per_24h,
        } => Some((min_gap_minutes, max_per_24h)),
        _ => None,
    }
}
