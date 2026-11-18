//! `local-day`: the local calendar day, across the clock changes.

use serde_json::{Value, json};
use taskrx_desktop_core::time::{
    at_clock_time_on_day_of, local_calendar_days_between, local_day_bounds, taken_at_or_null,
};

use super::{Outcome, int, text, unknown_call, zone};

pub fn case(input: &Value) -> Outcome {
    let tz = zone(input)?;
    match text(input, "call")? {
        "localDayBounds" => {
            let b = local_day_bounds(int(input, "now")?, int(input, "offsetDays")? as i32, &tz);
            Ok(json!({ "start": b.start, "end": b.end }))
        }
        "localCalendarDaysBetween" => Ok(json!(local_calendar_days_between(
            int(input, "from")?,
            int(input, "to")?,
            &tz
        ))),
        "atClockTimeOnDayOf" => Ok(json!(at_clock_time_on_day_of(
            int(input, "day")?,
            int(input, "hour")? as i8,
            int(input, "minute")? as i8,
            &tz
        ))),
        "takenAtOrNull" => Ok(json!(taken_at_or_null(
            int(input, "scheduledTime")?,
            int(input, "hour")? as i8,
            int(input, "minute")? as i8,
            int(input, "now")?,
            &tz,
        ))),
        _ => unknown_call(input),
    }
}
