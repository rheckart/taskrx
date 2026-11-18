//! The local calendar day. Kotlin: `core/time/LocalDay.kt`.
//!
//! Built from the calendar date, never from `now ± n × 86 400 000`: a local day is 23 or 25
//! hours twice a year. A wall-clock time inside a spring-forward gap moves later by the gap,
//! and one inside a fall-back overlap takes the earlier offset, as `java.time` resolves them;
//! jiff's "compatible" disambiguation is the same rule.

use jiff::civil::Date;
use jiff::tz::TimeZone;
use jiff::{Span, Timestamp};

/// Midnight to the last millisecond of a local calendar day.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LocalDayBounds {
    pub start: i64,
    pub end: i64,
}

/// The zone for an IANA name, from the bundled database.
pub fn zone(name: &str) -> Option<TimeZone> {
    TimeZone::get(name).ok()
}

/// The local date holding the instant `ms`.
pub fn local_date(ms: i64, tz: &TimeZone) -> Date {
    Timestamp::from_millisecond(ms)
        .expect("a moment in range")
        .to_zoned(tz.clone())
        .date()
}

/// The instant at a local wall-clock time, gaps moving later and overlaps taking the earlier offset.
pub fn instant_at(
    date: Date,
    hour: i8,
    minute: i8,
    second: i8,
    millisecond: i32,
    tz: &TimeZone,
) -> i64 {
    date.at(hour, minute, second, millisecond * 1_000_000)
        .to_zoned(tz.clone())
        .expect("a wall-clock time in range")
        .timestamp()
        .as_millisecond()
}

/// The local calendar day `offset_days` from the day holding `now`. `end` is the day's last
/// millisecond, so a caller comparing with `<=` does not catch 00:00 tomorrow.
pub fn local_day_bounds(now: i64, offset_days: i32, tz: &TimeZone) -> LocalDayBounds {
    let day = local_date(now, tz)
        .checked_add(Span::new().days(offset_days))
        .expect("a day in range");
    LocalDayBounds {
        // The first instant of the day: after the gap, when midnight falls in one.
        start: day
            .to_zoned(tz.clone())
            .expect("a day in range")
            .timestamp()
            .as_millisecond(),
        end: instant_at(day, 23, 59, 59, 999, tz),
    }
}

/// How many local calendar days separate the day holding `from_ms` from the day holding
/// `to_ms`, counted between midnights and rounded half up, so a 23- or 25-hour day counts as one.
pub fn local_calendar_days_between(from_ms: i64, to_ms: i64, tz: &TimeZone) -> i32 {
    let from = local_day_bounds(from_ms, 0, tz).start;
    let to = local_day_bounds(to_ms, 0, tz).start;
    ((to - from) as f64 / 86_400_000.0 + 0.5).floor() as i32
}

/// The instant at `hour:minute` on the local day holding `day_ms`: only the clock changes.
pub fn at_clock_time_on_day_of(day_ms: i64, hour: i8, minute: i8, tz: &TimeZone) -> i64 {
    instant_at(local_date(day_ms, tz), hour, minute, 0, 0, tz)
}

/// When a dose scheduled for `scheduled_time` was taken, given the clock time the person says,
/// or `None` when that is still ahead of `now`.
pub fn taken_at_or_null(
    scheduled_time: i64,
    hour: i8,
    minute: i8,
    now: i64,
    tz: &TimeZone,
) -> Option<i64> {
    Some(at_clock_time_on_day_of(scheduled_time, hour, minute, tz)).filter(|t| *t <= now)
}
