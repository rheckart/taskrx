//! How times, days and statuses read on screen.

use jiff::Timestamp;
use jiff::tz::TimeZone;
use taskrx_desktop_core::domain::InstanceStatus;

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
}
