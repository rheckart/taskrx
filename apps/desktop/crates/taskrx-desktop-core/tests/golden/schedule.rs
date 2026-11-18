//! `instance-id` and `server-schedule`.

use serde_json::{Value, json};
use taskrx_desktop_core::domain::instance_id_for;
use taskrx_desktop_core::sync::server_schedule::{schedule_from_server, schedule_to_server};

use taskrx_desktop_core::schedule::{
    ScheduledOccurrence, latest_occurrence_before, next_occurrences,
};
use taskrx_desktop_core::sync::server_docs::{self, reminder_to_pulled};

use super::{Outcome, field, int, text, unknown_call, zone};

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

fn occurrence(o: &ScheduledOccurrence) -> Value {
    json!({ "occurrenceId": o.occurrence_id, "reminderId": o.reminder_id, "name": o.name, "dosage": o.dosage, "fireAt": o.fire_at })
}

/// A Convex `reminders` document as the rules read it.
pub fn reminder(doc: &Value) -> Result<taskrx_desktop_core::domain::Reminder, String> {
    let docs =
        server_docs::reminders(&Value::Array(vec![doc.clone()])).map_err(|e| e.to_string())?;
    let first = docs.first().ok_or("empty list")?;
    Ok(reminder_to_pulled(first)
        .ok_or("a reminder of an unknown kind")?
        .reminder)
}

/// `schedule`: the Occurrence engine.
pub fn occurrences(input: &Value) -> Outcome {
    let tz = zone(input)?;
    let r = reminder(field(input, "reminder")?)?;
    match text(input, "call")? {
        "nextOccurrences" => Ok(
            match next_occurrences(&r, int(input, "fromMs")?, int(input, "count")?, &tz) {
                Ok(list) => Value::Array(list.iter().map(occurrence).collect()),
                Err(_) => json!({ "refused": "invalidArgument" }),
            },
        ),
        "latestOccurrenceBefore" => Ok(latest_occurrence_before(&r, int(input, "now")?, &tz)
            .map_or(Value::Null, |o| occurrence(&o))),
        _ => unknown_call(input),
    }
}
