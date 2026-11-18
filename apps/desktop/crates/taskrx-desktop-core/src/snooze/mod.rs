//! How long a snooze lasts, and putting a moment off to a time later today.
//! Kotlin: `core/snooze/Snooze.kt`.

use jiff::tz::TimeZone;

use crate::time::{instant_at, local_date};

pub const SNOOZE_CHOICES_MINUTES: [i32; 5] = [5, 10, 15, 30, 60];
pub const DEFAULT_SNOOZE_MINUTES: i32 = 15;

/// The stored choice when it is one of the offered ones, else the default.
pub fn snooze_minutes_or_default(stored: Option<i32>) -> i32 {
    stored
        .filter(|m| SNOOZE_CHOICES_MINUTES.contains(m))
        .unwrap_or(DEFAULT_SNOOZE_MINUTES)
}

pub fn snooze_until(now: i64, minutes: i32) -> i64 {
    now + i64::from(minutes) * 60_000
}

/// `hour:minute` today, local, when that is still ahead of `now`; `None` otherwise or for a time
/// that is not one.
pub fn reschedule_instant(now: i64, hour: i64, minute: i64, tz: &TimeZone) -> Option<i64> {
    if !(0..=23).contains(&hour) || !(0..=59).contains(&minute) {
        return None;
    }
    let picked = instant_at(local_date(now, tz), hour as i8, minute as i8, 0, 0, tz);
    (picked > now).then_some(picked)
}

/// Whether any time is still left today to put something off to.
pub fn can_pick_a_time_today(now: i64, tz: &TimeZone) -> bool {
    reschedule_instant(now, 23, 59, tz).is_some()
}
