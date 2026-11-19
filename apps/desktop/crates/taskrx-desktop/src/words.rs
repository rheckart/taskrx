//! How times, days and statuses read on screen.

use jiff::Timestamp;
use jiff::tz::TimeZone;
use taskrx_desktop_core::domain::{InstanceStatus, ScheduleTimes};

/// "9:00 AM".
pub fn clock(ms: i64, zone: &TimeZone) -> String {
    match Timestamp::from_millisecond(ms) {
        Ok(t) => t.to_zoned(zone.clone()).strftime("%-I:%M %p").to_string(),
        Err(_) => String::new(),
    }
}

/// "Wednesday, November 18".
pub fn day(ms: i64, zone: &TimeZone) -> String {
    match Timestamp::from_millisecond(ms) {
        Ok(t) => t.to_zoned(zone.clone()).strftime("%A, %B %-d").to_string(),
        Err(_) => String::new(),
    }
}

/// The day shown, relative to today where that is shorter.
pub fn day_heading(offset: i32, day_start: i64, zone: &TimeZone) -> String {
    match offset {
        0 => "Today".to_string(),
        -1 => "Yesterday".to_string(),
        1 => "Tomorrow".to_string(),
        _ => day(day_start, zone),
    }
}

pub fn status(status: InstanceStatus) -> &'static str {
    match status {
        InstanceStatus::Pending => "Due",
        InstanceStatus::Completed => "Taken",
        InstanceStatus::Skipped => "Skipped",
        InstanceStatus::Snoozed => "Snoozed",
        InstanceStatus::Missed => "Missed",
    }
}

/// A task is done, not taken.
pub fn task_status(status: InstanceStatus) -> &'static str {
    match status {
        InstanceStatus::Completed => "Done",
        InstanceStatus::Missed => "Overdue",
        other => self::status(other),
    }
}

/// "08:30" as "8:30 AM"; anything else as written.
pub fn wall_time(hhmm: &str) -> String {
    let Some((h, m)) = hhmm.split_once(':') else {
        return hhmm.to_string();
    };
    match (h.parse::<u32>(), m.parse::<u32>()) {
        (Ok(h), Ok(m)) if h < 24 && m < 60 => {
            let (h12, half) = match h {
                0 => (12, "AM"),
                1..=11 => (h, "AM"),
                12 => (12, "PM"),
                _ => (h - 12, "PM"),
            };
            format!("{h12}:{m:02} {half}")
        }
        _ => hhmm.to_string(),
    }
}

fn times(list: &[String]) -> String {
    list.iter()
        .map(|t| wall_time(t))
        .collect::<Vec<_>>()
        .join(", ")
}

fn ordinal(n: i32) -> String {
    let suffix = match (n % 10, n % 100) {
        (1, 11) | (2, 12) | (3, 13) => "th",
        (1, _) => "st",
        (2, _) => "nd",
        (3, _) => "rd",
        _ => "th",
    };
    format!("{n}{suffix}")
}

/// A schedule in a line.
pub fn schedule(s: &ScheduleTimes, zone: &TimeZone) -> String {
    const DAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    match s {
        ScheduleTimes::Daily { times: t } => format!("Every day at {}", times(t)),
        ScheduleTimes::Interval { hours, start_at } => {
            format!("Every {hours} hours from {}", clock(*start_at, zone))
        }
        ScheduleTimes::Weekly {
            days_of_week,
            times: t,
        } => {
            let days: Vec<&str> = days_of_week
                .iter()
                .filter_map(|d| DAYS.get(usize::try_from(*d).ok()?).copied())
                .collect();
            format!("{} at {}", days.join(", "), times(t))
        }
        ScheduleTimes::Monthly {
            days_of_month,
            times: t,
        } => {
            let dates: Vec<String> = days_of_month.iter().map(|d| ordinal(*d)).collect();
            format!("Monthly on the {} at {}", dates.join(", "), times(t))
        }
        ScheduleTimes::Once { at } => format!("Once, {} at {}", day(*at, zone), clock(*at, zone)),
        ScheduleTimes::None => "No set time (Backlog)".to_string(),
        ScheduleTimes::AsNeeded {
            min_gap_minutes,
            max_per_24h,
        } => {
            let gap = if min_gap_minutes % 60 == 0 {
                format!("{} h", min_gap_minutes / 60)
            } else {
                format!("{min_gap_minutes} min")
            };
            match max_per_24h {
                Some(max) => format!("As needed, {gap} apart, up to {max} a day"),
                None => format!("As needed, {gap} apart"),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn times_and_days_read_in_the_zone() {
        let zone = TimeZone::get("America/New_York").unwrap();
        let ms = "2026-11-18T21:05[America/New_York]"
            .parse::<jiff::Zoned>()
            .unwrap()
            .timestamp()
            .as_millisecond();
        assert_eq!(clock(ms, &zone), "9:05 PM");
        assert_eq!(day(ms, &zone), "Wednesday, November 18");
        assert_eq!(day_heading(0, ms, &zone), "Today");
        assert_eq!(day_heading(-3, ms, &zone), "Wednesday, November 18");
    }

    #[test]
    fn schedules_read_in_a_line() {
        let zone = TimeZone::get("America/New_York").unwrap();
        assert_eq!(
            schedule(
                &ScheduleTimes::Daily {
                    times: vec!["08:00".into(), "20:30".into()]
                },
                &zone
            ),
            "Every day at 8:00 AM, 8:30 PM"
        );
        assert_eq!(
            schedule(
                &ScheduleTimes::Monthly {
                    days_of_month: vec![1, 22],
                    times: vec!["00:15".into()]
                },
                &zone
            ),
            "Monthly on the 1st, 22nd at 12:15 AM"
        );
        assert_eq!(
            schedule(
                &ScheduleTimes::AsNeeded {
                    min_gap_minutes: 240,
                    max_per_24h: Some(4)
                },
                &zone
            ),
            "As needed, 4 h apart, up to 4 a day"
        );
    }
}
