//! Giving a Backlog item a time: the phone's "Give it a time" picker as typed fields. Mirrors
//! the phone's `ScheduleEditing.kt` (the variants offered, where each starts) and its
//! `canMoveToTimeline`: what is savable is core's `is_well_formed_schedule`, and the Backlog's
//! own "no time" is not a time to move to.
//!
//! As needed is not offered, as on the phone's Backlog: it is a way to take a medication, set
//! from the Reminder's edit screen.

use jiff::civil::{Date, Time};
use jiff::tz::TimeZone;
use jiff::{Timestamp, ToSpan, Zoned};
use taskrx_desktop_core::domain::ScheduleTimes;
use taskrx_desktop_core::parser::is_well_formed_schedule;

/// The variants offered, in the phone's order.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimingKind {
    Daily,
    Weekly,
    Monthly,
    Interval,
    Once,
}

impl TimingKind {
    pub const ALL: [TimingKind; 5] = [
        TimingKind::Daily,
        TimingKind::Weekly,
        TimingKind::Monthly,
        TimingKind::Interval,
        TimingKind::Once,
    ];

    pub fn label(self) -> &'static str {
        match self {
            TimingKind::Daily => "Daily",
            TimingKind::Weekly => "Weekly",
            TimingKind::Monthly => "Monthly",
            TimingKind::Interval => "Interval",
            TimingKind::Once => "Once",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IntervalUnit {
    Hours,
    Days,
    Weeks,
}

impl IntervalUnit {
    pub const ALL: [IntervalUnit; 3] =
        [IntervalUnit::Hours, IntervalUnit::Days, IntervalUnit::Weeks];

    pub fn label(self) -> &'static str {
        match self {
            IntervalUnit::Hours => "Hours",
            IntervalUnit::Days => "Days",
            IntervalUnit::Weeks => "Weeks",
        }
    }

    fn hours(self) -> i32 {
        match self {
            IntervalUnit::Hours => 1,
            IntervalUnit::Days => 24,
            IntervalUnit::Weeks => 168,
        }
    }
}

/// The picker's fields as typed. Only the ones the kind uses are read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TimingForm {
    pub kind: TimingKind,
    /// Daily, Weekly, Monthly: "9:00 AM, 9 pm".
    pub times: String,
    /// Weekly: Sunday is 0.
    pub days_of_week: Vec<i32>,
    /// Monthly: "1, 15".
    pub days_of_month: String,
    /// Interval: how many units apart.
    pub every: String,
    pub unit: IntervalUnit,
    /// Once, and an Interval's first: "today", "tomorrow", "Friday", "Nov 20", "2026-11-20".
    pub date: String,
    /// Once, and an Interval's first: "3 pm".
    pub at: String,
}

/// Where a variant starts the person off, as the phone's `defaultSchedule`: daily at 9, weekdays
/// at 9, the first of the month at 9, every 8 hours from now, once an hour from now. Moments are
/// rounded up to the quarter hour, so they read as a person would type them.
pub fn form_for(kind: TimingKind, now: i64, zone: &TimeZone) -> TimingForm {
    let quarter = 15 * 60_000;
    let start = |from: i64| {
        let at = (from + quarter - 1) / quarter * quarter;
        Timestamp::from_millisecond(at)
            .map(|t| t.to_zoned(zone.clone()))
            .ok()
    };
    let (date, at) = match start(if kind == TimingKind::Once {
        now + 3_600_000
    } else {
        now
    }) {
        Some(z) => (
            if z.date() == today(now, zone) {
                "today".to_string()
            } else {
                "tomorrow".to_string()
            },
            z.strftime("%-I:%M %p").to_string(),
        ),
        None => ("today".to_string(), String::new()),
    };
    TimingForm {
        kind,
        times: "9:00 AM".to_string(),
        days_of_week: vec![1, 2, 3, 4, 5],
        days_of_month: "1".to_string(),
        every: "8".to_string(),
        unit: IntervalUnit::Hours,
        date,
        at,
    }
}

/// A day switched on or off, the list kept in order (the phone's `toggleDay`).
pub fn toggle_day(days: &[i32], day: i32) -> Vec<i32> {
    let mut out: Vec<i32> = days.iter().copied().filter(|d| *d != day).collect();
    if out.len() == days.len() {
        out.push(day);
    }
    out.sort_unstable();
    out
}

/// Which field stops the schedule from being read.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TimingProblem {
    Times,
    DaysOfWeek,
    DaysOfMonth,
    Every,
    Date,
    At,
}

impl TimingProblem {
    pub fn words(self) -> &'static str {
        match self {
            TimingProblem::Times => "Type one or more times, like 9:00 AM, 9 pm",
            TimingProblem::DaysOfWeek => "Choose at least one day",
            TimingProblem::DaysOfMonth => "Type days of the month from 1 to 31, like 1, 15",
            TimingProblem::Every => "Type how many, a whole number from 1",
            TimingProblem::Date => "Type a day: today, tomorrow, Friday, Nov 20 or 2026-11-20",
            TimingProblem::At => "Type a time, like 3 pm or 15:00",
        }
    }
}

/// The schedule the form describes, or the field that is not readable.
pub fn read_form(
    form: &TimingForm,
    now: i64,
    zone: &TimeZone,
) -> Result<ScheduleTimes, TimingProblem> {
    let schedule = match form.kind {
        TimingKind::Daily => ScheduleTimes::Daily {
            times: read_times(&form.times)?,
        },
        TimingKind::Weekly => {
            let times = read_times(&form.times)?;
            if form.days_of_week.is_empty() {
                return Err(TimingProblem::DaysOfWeek);
            }
            ScheduleTimes::Weekly {
                days_of_week: form.days_of_week.clone(),
                times,
            }
        }
        TimingKind::Monthly => {
            let days = read_days_of_month(&form.days_of_month).ok_or(TimingProblem::DaysOfMonth)?;
            ScheduleTimes::Monthly {
                days_of_month: days,
                times: read_times(&form.times)?,
            }
        }
        TimingKind::Interval => {
            let every: i32 = form
                .every
                .trim()
                .parse()
                .ok()
                .filter(|n| *n > 0)
                .ok_or(TimingProblem::Every)?;
            let hours = every
                .checked_mul(form.unit.hours())
                .ok_or(TimingProblem::Every)?;
            ScheduleTimes::Interval {
                hours,
                start_at: read_moment(&form.date, &form.at, now, zone)?,
            }
        }
        TimingKind::Once => ScheduleTimes::Once {
            at: read_moment(&form.date, &form.at, now, zone)?,
        },
    };
    if is_well_formed_schedule(&schedule) {
        Ok(schedule)
    } else {
        Err(match form.kind {
            TimingKind::Interval => TimingProblem::Every,
            TimingKind::Once => TimingProblem::Date,
            _ => TimingProblem::Times,
        })
    }
}

/// The moment a Once fires, or an Interval first does, when it is already past: the phone
/// warns "nothing will fire for it" and lets it be saved.
pub fn in_the_past(schedule: &ScheduleTimes, now: i64) -> bool {
    match schedule {
        ScheduleTimes::Once { at } => *at <= now,
        ScheduleTimes::Interval { start_at, .. } => *start_at <= now,
        _ => false,
    }
}

fn today(now: i64, zone: &TimeZone) -> Date {
    Timestamp::from_millisecond(now)
        .map(|t| t.to_zoned(zone.clone()).date())
        .unwrap_or_default()
}

/// "9", "9am", "9:30 pm", "21:00", "noon", "midnight" as "HH:MM".
pub fn read_clock(text: &str) -> Option<String> {
    let t = text.trim().to_lowercase().replace('.', "");
    match t.as_str() {
        "noon" => return Some("12:00".into()),
        "midnight" => return Some("00:00".into()),
        _ => {}
    }
    let (digits, half) = if let Some(d) = t.strip_suffix("am").or_else(|| t.strip_suffix('a')) {
        (d.trim(), Some(false))
    } else if let Some(d) = t.strip_suffix("pm").or_else(|| t.strip_suffix('p')) {
        (d.trim(), Some(true))
    } else {
        (t.as_str(), None)
    };
    let (h, m) = match digits.split_once(':') {
        Some((h, m)) if m.len() == 2 => (h.parse::<u32>().ok()?, m.parse::<u32>().ok()?),
        Some(_) => return None,
        None => (digits.parse::<u32>().ok()?, 0),
    };
    if m > 59 {
        return None;
    }
    let h = match half {
        None if h < 24 => h,
        Some(pm) if (1..=12).contains(&h) => (h % 12) + if pm { 12 } else { 0 },
        _ => return None,
    };
    Some(format!("{h:02}:{m:02}"))
}

fn read_times(text: &str) -> Result<Vec<String>, TimingProblem> {
    let mut times = Vec::new();
    for part in text.split([',', ';']).flat_map(|p| p.split(" and ")) {
        if part.trim().is_empty() {
            continue;
        }
        times.push(read_clock(part).ok_or(TimingProblem::Times)?);
    }
    times.sort();
    times.dedup();
    if times.is_empty() {
        return Err(TimingProblem::Times);
    }
    Ok(times)
}

fn read_days_of_month(text: &str) -> Option<Vec<i32>> {
    let mut days = Vec::new();
    for part in text.split([',', ' ', ';']).filter(|p| !p.trim().is_empty()) {
        let digits = part
            .trim()
            .trim_end_matches(|c: char| c.is_ascii_alphabetic());
        let d: i32 = digits.parse().ok()?;
        if !(1..=31).contains(&d) {
            return None;
        }
        days.push(d);
    }
    days.sort_unstable();
    days.dedup();
    (!days.is_empty()).then_some(days)
}

const MONTHS: [&str; 12] = [
    "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
];
const WEEKDAYS: [&str; 7] = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"];

/// "today", "tomorrow", a weekday (the next one, today included), "Nov 20", "20 Nov", "11/20",
/// "11/20/2026", "2026-11-20". A month and day with no year is the next such day.
pub fn read_date(text: &str, now: i64, zone: &TimeZone) -> Option<Date> {
    let today = today(now, zone);
    let t = text.trim().to_lowercase().replace(',', " ");
    let words: Vec<&str> = t.split_whitespace().collect();
    match words.as_slice() {
        ["today"] => return Some(today),
        ["tomorrow"] => return today.checked_add(1.day()).ok(),
        [w] if w.len() >= 3 && WEEKDAYS.iter().any(|d| w.starts_with(d)) => {
            let want = WEEKDAYS.iter().position(|d| w.starts_with(d))? as i32;
            let have = i32::from(today.weekday().to_sunday_zero_offset());
            return today.checked_add(((want - have).rem_euclid(7)).days()).ok();
        }
        _ => {}
    }
    if let Ok(d) = t.parse::<Date>() {
        return Some(d);
    }
    let month_of = |w: &str| {
        MONTHS
            .iter()
            .position(|m| w.starts_with(m))
            .map(|i| i as i8 + 1)
    };
    let (month, day, year) = match words.as_slice() {
        [m, d] if month_of(m).is_some() => (
            month_of(m)?,
            d.trim_end_matches(char::is_alphabetic).parse().ok()?,
            None,
        ),
        [d, m] if month_of(m).is_some() => (
            month_of(m)?,
            d.trim_end_matches(char::is_alphabetic).parse().ok()?,
            None,
        ),
        [m, d, y] if month_of(m).is_some() => (
            month_of(m)?,
            d.trim_end_matches(char::is_alphabetic).parse().ok()?,
            Some(y.parse().ok()?),
        ),
        [slashed] if slashed.contains('/') => {
            let parts: Vec<&str> = slashed.split('/').collect();
            match parts.as_slice() {
                [m, d] => (m.parse().ok()?, d.parse().ok()?, None),
                [m, d, y] => (m.parse().ok()?, d.parse().ok()?, Some(y.parse().ok()?)),
                _ => return None,
            }
        }
        _ => return None,
    };
    match year {
        Some(y) => Date::new(y, month, day).ok(),
        None => {
            let this = Date::new(today.year(), month, day).ok()?;
            if this >= today {
                Some(this)
            } else {
                Date::new(today.year() + 1, month, day).ok()
            }
        }
    }
}

fn read_moment(date: &str, at: &str, now: i64, zone: &TimeZone) -> Result<i64, TimingProblem> {
    let day = read_date(date, now, zone).ok_or(TimingProblem::Date)?;
    let clock = read_clock(at).ok_or(TimingProblem::At)?;
    let (h, m) = clock.split_once(':').ok_or(TimingProblem::At)?;
    let time = Time::new(
        h.parse().map_err(|_| TimingProblem::At)?,
        m.parse().map_err(|_| TimingProblem::At)?,
        0,
        0,
    )
    .map_err(|_| TimingProblem::At)?;
    let zoned: Zoned = day
        .to_datetime(time)
        .to_zoned(zone.clone())
        .map_err(|_| TimingProblem::Date)?;
    Ok(zoned.timestamp().as_millisecond())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn zone() -> TimeZone {
        TimeZone::get("America/New_York").unwrap()
    }

    /// Thursday 2026-11-19 17:31 in New York.
    fn now() -> i64 {
        "2026-11-19T17:31[America/New_York]"
            .parse::<Zoned>()
            .unwrap()
            .timestamp()
            .as_millisecond()
    }

    fn at(s: &str) -> i64 {
        format!("{s}[America/New_York]")
            .parse::<Zoned>()
            .unwrap()
            .timestamp()
            .as_millisecond()
    }

    #[test]
    fn clocks_are_read_as_people_type_them() {
        for (typed, read) in [
            ("9", "09:00"),
            ("9am", "09:00"),
            ("9 AM", "09:00"),
            ("9:30 pm", "21:30"),
            ("12 am", "00:00"),
            ("12pm", "12:00"),
            ("21:00", "21:00"),
            ("noon", "12:00"),
            ("9 p.m.", "21:00"),
        ] {
            assert_eq!(read_clock(typed).as_deref(), Some(read), "{typed}");
        }
        for typed in ["13 pm", "9:5", "25:00", "9:60", "nine", ""] {
            assert_eq!(read_clock(typed), None, "{typed}");
        }
    }

    #[test]
    fn days_are_read_from_today() {
        let (n, z) = (now(), zone());
        let d = |y, m, day| Date::new(y, m, day).unwrap();
        assert_eq!(read_date("today", n, &z), Some(d(2026, 11, 19)));
        assert_eq!(read_date("Tomorrow", n, &z), Some(d(2026, 11, 20)));
        assert_eq!(read_date("thursday", n, &z), Some(d(2026, 11, 19)));
        assert_eq!(read_date("Mon", n, &z), Some(d(2026, 11, 23)));
        assert_eq!(read_date("Nov 20", n, &z), Some(d(2026, 11, 20)));
        assert_eq!(read_date("20 November", n, &z), Some(d(2026, 11, 20)));
        assert_eq!(read_date("Jan 3", n, &z), Some(d(2027, 1, 3)));
        assert_eq!(read_date("11/20", n, &z), Some(d(2026, 11, 20)));
        assert_eq!(read_date("2027-02-01", n, &z), Some(d(2027, 2, 1)));
        assert_eq!(read_date("Feb 30", n, &z), None);
        assert_eq!(read_date("someday", n, &z), None);
    }

    #[test]
    fn each_kind_starts_where_the_phone_starts_it() {
        let (n, z) = (now(), zone());
        assert_eq!(
            read_form(&form_for(TimingKind::Daily, n, &z), n, &z),
            Ok(ScheduleTimes::Daily {
                times: vec!["09:00".into()]
            })
        );
        assert_eq!(
            read_form(&form_for(TimingKind::Weekly, n, &z), n, &z),
            Ok(ScheduleTimes::Weekly {
                days_of_week: vec![1, 2, 3, 4, 5],
                times: vec!["09:00".into()]
            })
        );
        assert_eq!(
            read_form(&form_for(TimingKind::Monthly, n, &z), n, &z),
            Ok(ScheduleTimes::Monthly {
                days_of_month: vec![1],
                times: vec!["09:00".into()]
            })
        );
        assert_eq!(
            read_form(&form_for(TimingKind::Interval, n, &z), n, &z),
            Ok(ScheduleTimes::Interval {
                hours: 8,
                start_at: at("2026-11-19T17:45")
            })
        );
        // An hour out, on the quarter hour: 18:31 rounds to 18:45.
        assert_eq!(
            read_form(&form_for(TimingKind::Once, n, &z), n, &z),
            Ok(ScheduleTimes::Once {
                at: at("2026-11-19T18:45")
            })
        );
    }

    #[test]
    fn typed_fields_make_the_schedule() {
        let (n, z) = (now(), zone());
        let mut f = form_for(TimingKind::Daily, n, &z);
        f.times = "9 pm, 8am and 9pm".into();
        assert_eq!(
            read_form(&f, n, &z),
            Ok(ScheduleTimes::Daily {
                times: vec!["08:00".into(), "21:00".into()]
            })
        );
        f.kind = TimingKind::Once;
        f.date = "tomorrow".into();
        f.at = "3 pm".into();
        assert_eq!(
            read_form(&f, n, &z),
            Ok(ScheduleTimes::Once {
                at: at("2026-11-20T15:00")
            })
        );
        f.kind = TimingKind::Interval;
        f.every = "2".into();
        f.unit = IntervalUnit::Days;
        assert_eq!(
            read_form(&f, n, &z),
            Ok(ScheduleTimes::Interval {
                hours: 48,
                start_at: at("2026-11-20T15:00")
            })
        );
        f.kind = TimingKind::Monthly;
        f.days_of_month = "15th, 1".into();
        f.times = "8".into();
        assert_eq!(
            read_form(&f, n, &z),
            Ok(ScheduleTimes::Monthly {
                days_of_month: vec![1, 15],
                times: vec!["08:00".into()]
            })
        );
    }

    #[test]
    fn an_unreadable_field_is_named() {
        let (n, z) = (now(), zone());
        let mut f = form_for(TimingKind::Daily, n, &z);
        f.times = "nine".into();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::Times));
        f.times = " ".into();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::Times));
        f.kind = TimingKind::Weekly;
        f.times = "9".into();
        f.days_of_week.clear();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::DaysOfWeek));
        f.kind = TimingKind::Monthly;
        f.days_of_month = "32".into();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::DaysOfMonth));
        f.kind = TimingKind::Interval;
        f.every = "0".into();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::Every));
        f.kind = TimingKind::Once;
        f.date = "someday".into();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::Date));
        f.date = "today".into();
        f.at = "later".into();
        assert_eq!(read_form(&f, n, &z), Err(TimingProblem::At));
    }

    #[test]
    fn a_moment_already_gone_is_flagged_not_refused() {
        let (n, z) = (now(), zone());
        let mut f = form_for(TimingKind::Once, n, &z);
        f.at = "9 am".into();
        let schedule = read_form(&f, n, &z).unwrap();
        assert!(in_the_past(&schedule, n));
        assert!(!in_the_past(
            &ScheduleTimes::Daily {
                times: vec!["09:00".into()]
            },
            n
        ));
    }

    #[test]
    fn days_toggle_in_order() {
        assert_eq!(toggle_day(&[1, 5], 3), vec![1, 3, 5]);
        assert_eq!(toggle_day(&[1, 3, 5], 3), vec![1, 5]);
    }
}
