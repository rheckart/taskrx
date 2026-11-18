//! `answer`: as-needed doses and snoozes.

use serde_json::{Value, json};
use taskrx_desktop_core::asneeded::{
    AsNeededWait, as_needed_of, as_needed_status, shows_on_today, takings,
};
use taskrx_desktop_core::domain::ScheduleTimes;
use taskrx_desktop_core::snooze::{
    DEFAULT_SNOOZE_MINUTES, SNOOZE_CHOICES_MINUTES, can_pick_a_time_today, reschedule_instant,
    snooze_minutes_or_default, snooze_until,
};
use taskrx_desktop_core::sync::server_schedule::{schedule_from_server, schedule_to_server};

use super::domain::instances;
use super::schedule::reminder;
use super::{Outcome, field, int, text, unknown_call, zone};

pub fn case(input: &Value) -> Outcome {
    match text(input, "call")? {
        "asNeededStatus" => {
            let Some(ScheduleTimes::AsNeeded {
                min_gap_minutes,
                max_per_24h,
            }) = schedule_from_server(Some(field(input, "schedule")?))
            else {
                return Err("not an as-needed schedule".into());
            };
            let taken: Vec<i64> = field(input, "takings")?
                .as_array()
                .ok_or("takings are not a list")?
                .iter()
                .filter_map(Value::as_f64)
                .map(|t| t as i64)
                .collect();
            let now = int(input, "now")?;
            let s = as_needed_status(min_gap_minutes, max_per_24h, &taken, now);
            Ok(json!({
                "lastTakenAt": s.last_taken_at, "takenIn24h": s.taken_in_24h, "readyAt": s.ready_at,
                "wait": s.wait.map(|w| match w { AsNeededWait::Gap => "gap", AsNeededWait::Limit => "limit" }),
                "openedAt": s.opened_at, "isReady": s.is_ready(now), "showsOnToday": shows_on_today(&s, now),
            }))
        }
        "takings" => Ok(json!(takings(
            &instances(field(input, "instances")?)?,
            text(input, "reminderId")?
        ))),
        "asNeededOf" => Ok(as_needed_of(&reminder(field(input, "reminder")?)?).map_or(
            Value::Null,
            |(min_gap_minutes, max_per_24h)| {
                schedule_to_server(&ScheduleTimes::AsNeeded {
                    min_gap_minutes,
                    max_per_24h,
                })
            },
        )),
        "constants" => {
            Ok(json!({ "choices": SNOOZE_CHOICES_MINUTES, "default": DEFAULT_SNOOZE_MINUTES }))
        }
        "snoozeMinutesOrDefault" => {
            let stored = match field(input, "stored")? {
                Value::Null => None,
                _ => Some(int(input, "stored")? as i32),
            };
            Ok(json!(snooze_minutes_or_default(stored)))
        }
        "snoozeUntil" => Ok(json!(snooze_until(
            int(input, "now")?,
            int(input, "minutes")? as i32
        ))),
        "rescheduleInstant" => Ok(json!(reschedule_instant(
            int(input, "now")?,
            int(input, "hour")?,
            int(input, "minute")?,
            &zone(input)?
        ))),
        "canPickATimeToday" => Ok(json!(can_pick_a_time_today(
            int(input, "now")?,
            &zone(input)?
        ))),
        _ => unknown_call(input),
    }
}
