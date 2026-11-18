//! The Occurrence engine. Kotlin: `core/schedule/Occurrences.kt`, `Types.kt`.
//!
//! Two behaviours a reimplementation gets wrong, kept here deliberately:
//!
//! - **The `from` boundary.** An occurrence landing exactly on `from` is excluded, for every
//!   variant: that is where an off-by-one produces a duplicate alarm as a dose comes due.
//! - **Calendar arithmetic.** Day-based schedules step local calendar days, so across a clock
//!   change a 09:00 dose stays at 09:00 and the gap between doses is what changes.

use std::collections::HashSet;

use jiff::ToSpan;
use jiff::civil::Date;
use jiff::tz::TimeZone;

use crate::domain::{DAYS_OF_MONTH, OccurrenceOverride, Reminder, ScheduleTimes, is_paused_at};
use crate::time::{instant_at, local_date};

const MS_PER_HOUR: i64 = 3_600_000;
const MS_PER_DAY: i64 = 24 * MS_PER_HOUR;
const DAILY_HORIZON_DAYS: i32 = 60;
const WEEKLY_HORIZON_DAYS: i32 = 56;
/// A year of local days, and a margin: the timeline asks a monthly reminder furthest ahead.
const MONTHLY_HORIZON_DAYS: i32 = 400;

/// One computed Occurrence, with what a notification shows. `dosage` is `None` for a task.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScheduledOccurrence {
    pub occurrence_id: String,
    pub reminder_id: String,
    pub name: String,
    pub dosage: Option<String>,
    pub fire_at: i64,
}

/// A count below zero: the call cannot be answered.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InvalidArgument(pub String);

/// `"H:MM"` or `"HH:MM"` as (hour, minute); malformed entries are dropped rather than trusted.
fn time_of_day(time: &str) -> Option<(i8, i8)> {
    let (h, m) = time.split_once(':')?;
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    if !(1..=2).contains(&h.len()) || m.len() != 2 || !digits(h) || !digits(m) {
        return None;
    }
    let (hour, minute): (i8, i8) = (h.parse().ok()?, m.parse().ok()?);
    (hour <= 23 && minute <= 59).then_some((hour, minute))
}

/// The parseable times, earliest first. A time listed twice stays twice, as in the reference.
fn sorted_times(times: &[String]) -> Vec<(i8, i8)> {
    let mut parsed: Vec<(i8, i8)> = times.iter().filter_map(|t| time_of_day(t)).collect();
    parsed.sort();
    parsed
}

/// Day index, 0 = Sunday.
fn weekday_of(day: Date) -> i32 {
    i32::from(day.weekday().to_sunday_zero_offset())
}

fn occurrence_at(source: &Reminder, fire_at: i64) -> ScheduledOccurrence {
    ScheduledOccurrence {
        occurrence_id: format!("{}:{fire_at}", source.id),
        reminder_id: source.id.clone(),
        name: source.name.clone(),
        dosage: source.dosage.clone(),
        fire_at,
    }
}

/// The shared walk for the day-based variants: local days from the one holding `from_ms`,
/// each of `times` on every day `on_day` admits, until `count` are found or the horizon is spent.
fn walk_local_days(
    source: &Reminder,
    times: &[(i8, i8)],
    from_ms: i64,
    count: usize,
    tz: &TimeZone,
    horizon_days: i32,
    on_day: impl Fn(Date) -> bool,
) -> Vec<ScheduledOccurrence> {
    let reference = local_date(from_ms, tz);
    let mut results = Vec::new();
    let mut offset = 0;
    while results.len() < count && offset < horizon_days {
        let day = reference
            .checked_add(offset.days())
            .expect("a day in range");
        if on_day(day) {
            for &(hour, minute) in times {
                let fire_at = instant_at(day, hour, minute, 0, 0, tz);
                if fire_at <= from_ms {
                    continue;
                }
                results.push(occurrence_at(source, fire_at));
                if results.len() >= count {
                    break;
                }
            }
        }
        offset += 1;
    }
    results
}

/// The date a `daysOfMonth` entry falls on in the month of `day`: the date itself, or the
/// month's last day when the month is shorter, so a 31st is never skipped.
fn day_of_month_in(day: Date, day_of_month: i32) -> Date {
    let last = i32::from(day.days_in_month());
    day.with()
        .day(day_of_month.min(last) as i8)
        .build()
        .expect("a date in the month")
}

fn scheduled_occurrences(
    reminder: &Reminder,
    from_ms: i64,
    count: usize,
    tz: &TimeZone,
) -> Vec<ScheduledOccurrence> {
    match &reminder.schedule {
        ScheduleTimes::Daily { times } => {
            let times = sorted_times(times);
            if times.is_empty() {
                return Vec::new();
            }
            walk_local_days(
                reminder,
                &times,
                from_ms,
                count,
                tz,
                DAILY_HORIZON_DAYS,
                |_| true,
            )
        }
        ScheduleTimes::Interval { hours, start_at } => {
            if *hours <= 0 {
                return Vec::new();
            }
            // Absolute: every N hours from `start_at`, whatever the calendar does.
            let interval = i64::from(*hours) * MS_PER_HOUR;
            let mut next = *start_at;
            if next <= from_ms {
                let steps = (from_ms - start_at).div_euclid(interval) + 1;
                next = start_at + steps * interval;
            }
            (0..count as i64)
                .map(|i| occurrence_at(reminder, next + i * interval))
                .collect()
        }
        ScheduleTimes::Weekly {
            days_of_week,
            times,
        } => {
            let times = sorted_times(times);
            if days_of_week.is_empty() || times.is_empty() {
                return Vec::new();
            }
            walk_local_days(
                reminder,
                &times,
                from_ms,
                count,
                tz,
                WEEKLY_HORIZON_DAYS,
                |day| days_of_week.contains(&weekday_of(day)),
            )
        }
        ScheduleTimes::Monthly {
            days_of_month,
            times,
        } => {
            let times = sorted_times(times);
            let days: Vec<i32> = days_of_month
                .iter()
                .copied()
                .filter(|d| DAYS_OF_MONTH.contains(&i64::from(*d)))
                .collect();
            if days.is_empty() || times.is_empty() {
                return Vec::new();
            }
            walk_local_days(
                reminder,
                &times,
                from_ms,
                count,
                tz,
                MONTHLY_HORIZON_DAYS,
                |day| days.iter().any(|d| day_of_month_in(day, *d) == day),
            )
        }
        ScheduleTimes::Once { at } => {
            if *at <= from_ms {
                Vec::new()
            } else {
                vec![occurrence_at(reminder, *at)]
            }
        }
        // The Backlog has no moments, and a medication taken as needed has none by design.
        ScheduleTimes::None | ScheduleTimes::AsNeeded { .. } => Vec::new(),
    }
}

/// Whether the Schedule still produces `moment` exactly. Pauses are not consulted.
fn is_scheduled_moment(reminder: &Reminder, moment: i64, tz: &TimeZone) -> bool {
    scheduled_occurrences(reminder, moment - 1, 1, tz)
        .first()
        .is_some_and(|o| o.fire_at == moment)
}

/// The Schedule's moments minus the paused ones, still `count` of them when it has that many:
/// each round asks for what is missing, and a pause with an end is jumped rather than walked.
fn unpaused_occurrences(
    reminder: &Reminder,
    from_ms: i64,
    count: usize,
    tz: &TimeZone,
) -> Vec<ScheduledOccurrence> {
    let mut results = Vec::new();
    let mut cursor = from_ms;
    while results.len() < count {
        if let Some(covering) = reminder
            .pauses
            .iter()
            .find(|p| is_paused_at(std::slice::from_ref(*p), cursor + 1))
        {
            let Some(until) = covering.until else { break };
            cursor = until - 1;
        }
        let batch = scheduled_occurrences(reminder, cursor, count - results.len(), tz);
        let Some(last) = batch.last().map(|o| o.fire_at) else {
            break;
        };
        results.extend(
            batch
                .into_iter()
                .filter(|o| !is_paused_at(&reminder.pauses, o.fire_at)),
        );
        cursor = last;
    }
    results
}

fn upcoming_occurrences(
    reminder: &Reminder,
    from_ms: i64,
    count: usize,
    tz: &TimeZone,
) -> Vec<ScheduledOccurrence> {
    if reminder.pauses.is_empty() {
        scheduled_occurrences(reminder, from_ms, count, tz)
    } else {
        unpaused_occurrences(reminder, from_ms, count, tz)
    }
}

/// The moves that still move something as of `from_ms`: a target ahead, a source the Schedule
/// still produces, and neither end paused (a pause wins over a move across it).
fn live_overrides<'a>(
    reminder: &'a Reminder,
    from_ms: i64,
    tz: &TimeZone,
) -> Vec<&'a OccurrenceOverride> {
    reminder
        .overrides
        .iter()
        .filter(|o| {
            o.to > from_ms
                && !is_paused_at(&reminder.pauses, o.from)
                && !is_paused_at(&reminder.pauses, o.to)
                && is_scheduled_moment(reminder, o.from, tz)
        })
        .collect()
}

/// The next `count` Occurrences strictly after `from_ms`, earliest first, with every live
/// move applied. Fewer when the schedule's horizon runs out (60 local days daily, 56 weekly,
/// 400 monthly); `interval` always gives `count`, `once` at most one, `none` nothing.
pub fn next_occurrences(
    reminder: &Reminder,
    from_ms: i64,
    count: i64,
    tz: &TimeZone,
) -> Result<Vec<ScheduledOccurrence>, InvalidArgument> {
    if count < 0 {
        return Err(InvalidArgument(format!(
            "count must be a non-negative integer, received {count}."
        )));
    }
    let count = count as usize;
    if count == 0 {
        return Ok(Vec::new());
    }
    let moved = live_overrides(reminder, from_ms, tz);
    if moved.is_empty() {
        return Ok(upcoming_occurrences(reminder, from_ms, count, tz));
    }
    // Asked for with room to spare: each applied move drops its own source from the batch.
    let sources: HashSet<i64> = moved.iter().map(|o| o.from).collect();
    let mut all: Vec<ScheduledOccurrence> =
        upcoming_occurrences(reminder, from_ms, count + moved.len(), tz)
            .into_iter()
            .filter(|o| !sources.contains(&o.fire_at))
            .chain(moved.iter().map(|o| occurrence_at(reminder, o.to)))
            .collect();
    // One row per moment, the first kept; then in order (a stable sort, as the reference's).
    let mut seen = HashSet::new();
    all.retain(|o| seen.insert(o.fire_at));
    all.sort_by_key(|o| o.fire_at);
    all.truncate(count);
    Ok(all)
}

/// The most recent moment the Schedule produced strictly before `now`, or `None`. The search
/// reaches one full cycle (two days daily, eight weekly, 32 monthly, one interval), the most
/// that can separate two moments; a longer pause leaves nothing, deliberately.
pub fn latest_occurrence_before(
    reminder: &Reminder,
    now: i64,
    tz: &TimeZone,
) -> Option<ScheduledOccurrence> {
    let before = |list: Vec<ScheduledOccurrence>| list.into_iter().rev().find(|o| o.fire_at < now);
    let (span_ms, count) = match &reminder.schedule {
        ScheduleTimes::Once { at } => {
            return before(next_occurrences(reminder, at - 1, 1, tz).ok()?);
        }
        ScheduleTimes::Interval { hours, .. } => {
            if *hours <= 0 {
                return None;
            }
            (i64::from(*hours) * MS_PER_HOUR, 2)
        }
        ScheduleTimes::Daily { times } => (2 * MS_PER_DAY, times.len() as i64 * 2 + 2),
        ScheduleTimes::Weekly {
            days_of_week,
            times,
        } => (
            8 * MS_PER_DAY,
            (days_of_week.len() * times.len()) as i64 * 2 + 2,
        ),
        ScheduleTimes::Monthly {
            days_of_month,
            times,
        } => (
            32 * MS_PER_DAY,
            (days_of_month.len() * times.len()) as i64 * 2 + 2,
        ),
        ScheduleTimes::None | ScheduleTimes::AsNeeded { .. } => return None,
    };
    // A millisecond before the span, so a moment exactly on the boundary is included.
    before(next_occurrences(reminder, now - span_ms - 1, count, tz).ok()?)
}
