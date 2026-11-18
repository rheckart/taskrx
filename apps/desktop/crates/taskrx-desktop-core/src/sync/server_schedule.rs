//! The schedule as Convex stores it, read into the domain union and written back.
//! Kotlin: `core/sync/ServerSchedule.kt`.

use serde_json::{Map, Value, json};

use crate::domain::reminder::whole;
use crate::domain::{DAYS_OF_MONTH, ScheduleTimes};

/// `"H:MM"` or `"HH:MM"` within the day, normalised to `"HH:MM"`.
fn time(value: &Value) -> Option<String> {
    let text = value.as_str()?;
    let (h, m) = text.split_once(':')?;
    let digits = |s: &str| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit());
    if !(1..=2).contains(&h.len()) || m.len() != 2 || !digits(h) || !digits(m) {
        return None;
    }
    let (hour, minute): (u32, u32) = (h.parse().ok()?, m.parse().ok()?);
    if hour > 23 || minute > 59 {
        return None;
    }
    Some(format!("{hour:02}:{minute:02}"))
}

fn times(value: Option<&Value>) -> Option<Vec<String>> {
    value?.as_array()?.iter().map(time).collect()
}

fn whole_in(value: &Value, range: std::ops::RangeInclusive<i64>) -> Option<i32> {
    whole(Some(value))
        .filter(|n| range.contains(n))
        .map(|n| n as i32)
}

fn weekdays(value: Option<&Value>) -> Option<Vec<i32>> {
    value?
        .as_array()?
        .iter()
        .map(|d| whole_in(d, 0..=6))
        .collect()
}

fn days_of_month(value: Option<&Value>) -> Option<Vec<i32>> {
    value?
        .as_array()?
        .iter()
        .map(|d| whole_in(d, DAYS_OF_MONTH))
        .collect()
}

/// A server schedule, or `None` for anything malformed (the pull files that under the Backlog).
pub fn schedule_from_server(value: Option<&Value>) -> Option<ScheduleTimes> {
    let o = value?.as_object()?;
    let positive = 1..=i64::from(i32::MAX);
    match o.get("type")?.as_str()? {
        "daily" => times(o.get("times")).map(|times| ScheduleTimes::Daily { times }),
        "interval" => {
            let hours = whole(o.get("hours")).filter(|h| positive.contains(h))?;
            let start_at = whole(o.get("startAt"))?;
            Some(ScheduleTimes::Interval {
                hours: hours as i32,
                start_at,
            })
        }
        "weekly" => Some(ScheduleTimes::Weekly {
            days_of_week: weekdays(o.get("daysOfWeek"))?,
            times: times(o.get("times"))?,
        }),
        "monthly" => Some(ScheduleTimes::Monthly {
            days_of_month: days_of_month(o.get("daysOfMonth"))?,
            times: times(o.get("times"))?,
        }),
        "once" => whole(o.get("at")).map(|at| ScheduleTimes::Once { at }),
        "none" => Some(ScheduleTimes::None),
        "asNeeded" => {
            let gap = whole(o.get("minGapMinutes")).filter(|g| positive.contains(g))?;
            // Absent is "no limit"; present but unreadable (an explicit null included) cannot be trusted.
            let max = match o.get("maxPer24h") {
                None => None,
                Some(m) => Some(whole(Some(m)).filter(|n| positive.contains(n))? as i32),
            };
            Some(ScheduleTimes::AsNeeded {
                min_gap_minutes: gap as i32,
                max_per_24h: max,
            })
        }
        _ => None,
    }
}

/// `schedule` in the Convex `scheduleTimesValidator` shape: what a create or update sends.
pub fn schedule_to_server(schedule: &ScheduleTimes) -> Value {
    match schedule {
        ScheduleTimes::Daily { times } => json!({ "type": "daily", "times": times }),
        ScheduleTimes::Interval { hours, start_at } => {
            json!({ "type": "interval", "hours": hours, "startAt": start_at })
        }
        ScheduleTimes::Weekly {
            days_of_week,
            times,
        } => json!({ "type": "weekly", "daysOfWeek": days_of_week, "times": times }),
        ScheduleTimes::Monthly {
            days_of_month,
            times,
        } => json!({ "type": "monthly", "daysOfMonth": days_of_month, "times": times }),
        ScheduleTimes::Once { at } => json!({ "type": "once", "at": at }),
        ScheduleTimes::None => json!({ "type": "none" }),
        ScheduleTimes::AsNeeded {
            min_gap_minutes,
            max_per_24h,
        } => {
            let mut o = Map::new();
            o.insert("type".into(), json!("asNeeded"));
            o.insert("minGapMinutes".into(), json!(min_gap_minutes));
            if let Some(max) = max_per_24h {
                o.insert("maxPer24h".into(), json!(max));
            }
            Value::Object(o)
        }
    }
}
