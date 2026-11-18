//! Catch up: the moments the sweep recorded as missed, to answer late. Kotlin:
//! `core/catchup/CatchUp.kt`.

use jiff::tz::TimeZone;

use crate::domain::InstanceStatus;
use crate::sweep::SWEEP_LOOKBACK_MS;
use crate::time::local_day_bounds;
use crate::timeline::TimelineRow;

/// As far back as the sweep reaches, since Catch up lists what the sweep recorded.
pub const CATCH_UP_WINDOW_MS: i64 = SWEEP_LOOKBACK_MS;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatchUpDay {
    pub day_start: i64,
    pub rows: Vec<TimelineRow>,
}

pub fn needs_catch_up(rows: &[TimelineRow]) -> Vec<TimelineRow> {
    rows.iter()
        .filter(|r| r.status == InstanceStatus::Missed)
        .cloned()
        .collect()
}

/// The rows by local day, newest day first, each day's rows in time order (stable).
pub fn catch_up_days(rows: &[TimelineRow], tz: &TimeZone) -> Vec<CatchUpDay> {
    let mut days: Vec<CatchUpDay> = Vec::new();
    for row in rows {
        let start = local_day_bounds(row.scheduled_time, 0, tz).start;
        match days.iter_mut().find(|d| d.day_start == start) {
            Some(day) => day.rows.push(row.clone()),
            None => days.push(CatchUpDay {
                day_start: start,
                rows: vec![row.clone()],
            }),
        }
    }
    for day in &mut days {
        day.rows.sort_by_key(|r| r.scheduled_time);
    }
    days.sort_by_key(|d| std::cmp::Reverse(d.day_start));
    days
}

/// The rows by moment, in first-seen order: what one batch answer covers.
pub fn catch_up_slots(rows: &[TimelineRow]) -> Vec<(i64, Vec<TimelineRow>)> {
    let mut slots: Vec<(i64, Vec<TimelineRow>)> = Vec::new();
    for row in rows {
        match slots.iter_mut().find(|(t, _)| *t == row.scheduled_time) {
            Some((_, slot)) => slot.push(row.clone()),
            None => slots.push((row.scheduled_time, vec![row.clone()])),
        }
    }
    slots
}
