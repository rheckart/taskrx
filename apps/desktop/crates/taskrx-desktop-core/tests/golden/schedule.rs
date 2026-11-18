//! `instance-id` and `server-schedule`.

use serde_json::{Value, json};
use taskrx_desktop_core::domain::instance_id_for;
use taskrx_desktop_core::sync::server_schedule::{schedule_from_server, schedule_to_server};

use super::{Outcome, field, int, text};

pub fn instance_id(input: &Value) -> Outcome {
    Ok(json!(instance_id_for(
        text(input, "reminderId")?,
        int(input, "scheduledTime")?
    )))
}

/// Read, then written back: the canonical shape, or null when unreadable.
pub fn server_schedule(input: &Value) -> Outcome {
    Ok(schedule_from_server(Some(field(input, "schedule")?))
        .map_or(Value::Null, |s| schedule_to_server(&s)))
}
