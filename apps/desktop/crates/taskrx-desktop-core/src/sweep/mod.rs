//! The Missed sweep. Kotlin: `core/sweep/Planner.kt`. The desktop shows its verdicts but
//! never writes them (ADR 0016).

use std::collections::HashSet;

use jiff::tz::TimeZone;

use crate::domain::{InstanceStatus, Reminder, ReminderInstance, ReminderKind};
use crate::schedule::next_occurrences;
use crate::timeline::timeline_row_key;

/// How long after its moment an unanswered Occurrence may still be answered on time (ADR 0005).
pub const GRACE_PERIOD_MS: i64 = 2 * 3_600_000;
/// How far back the sweep reaches.
pub const SWEEP_LOOKBACK_MS: i64 = 7 * 86_400_000;
/// The most Missed verdicts one Reminder may contribute to one sweep.
pub const MAX_MISSED_PER_REMINDER: i64 = 1000;

/// Whether a snooze nobody came back to has become the miss it was: its later of the moment
/// and the snooze end is more than a Grace Period behind `now`.
pub fn snooze_ran_out(instance: &ReminderInstance, now: i64, grace_period_ms: i64) -> bool {
    let from = instance
        .snoozed_until
        .map_or(instance.scheduled_time, |u| u.max(instance.scheduled_time));
    from < now - grace_period_ms
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MissedVerdict {
    pub reminder_id: String,
    pub reminder_kind: ReminderKind,
    pub scheduled_time: i64,
    /// The stored row it settles, or `None` for a moment that has no row yet.
    pub instance_id: Option<String>,
}

pub struct PlanMissedSweepInput<'a> {
    pub reminders: &'a [Reminder],
    pub instances: &'a [ReminderInstance],
    pub now: i64,
    pub grace_period_ms: i64,
    pub lookback_ms: i64,
    pub zone: &'a TimeZone,
}

/// The moments out of time in `[now - lookback, now - grace)`: stored pending rows and run-out
/// snoozes, then predicted moments with no row at all, never before the Reminder existed.
pub fn plan_missed_sweep(input: &PlanMissedSweepInput) -> Vec<MissedVerdict> {
    let floor = input.now - input.lookback_ms;
    let deadline = input.now - input.grace_period_ms;
    let out_of_time = |moment: i64| moment >= floor && moment < deadline;

    let mut verdicts = Vec::new();
    let mut claimed = HashSet::new();
    for stored in input.instances {
        if !out_of_time(stored.scheduled_time) {
            continue;
        }
        claimed.insert(timeline_row_key(&stored.reminder_id, stored.scheduled_time));
        let settles = stored.status == InstanceStatus::Pending
            || (stored.status == InstanceStatus::Snoozed
                && snooze_ran_out(stored, input.now, input.grace_period_ms));
        if !settles {
            continue;
        }
        verdicts.push(MissedVerdict {
            reminder_id: stored.reminder_id.clone(),
            reminder_kind: stored.reminder_kind,
            scheduled_time: stored.scheduled_time,
            instance_id: Some(stored.id.clone()),
        });
    }

    for reminder in input.reminders.iter().filter(|r| r.active) {
        // A moment exactly on `created_at` counts; nothing before it does.
        let from = floor.max(reminder.created_at) - 1;
        for occurrence in next_occurrences(reminder, from, MAX_MISSED_PER_REMINDER, input.zone)
            .unwrap_or_default()
        {
            if occurrence.fire_at >= deadline {
                break;
            }
            if claimed.contains(&timeline_row_key(&reminder.id, occurrence.fire_at)) {
                continue;
            }
            verdicts.push(MissedVerdict {
                reminder_id: reminder.id.clone(),
                reminder_kind: reminder.kind,
                scheduled_time: occurrence.fire_at,
                instance_id: None,
            });
        }
    }

    verdicts.sort_by(|a, b| {
        a.scheduled_time
            .cmp(&b.scheduled_time)
            .then_with(|| a.reminder_id.cmp(&b.reminder_id))
    });
    verdicts
}
