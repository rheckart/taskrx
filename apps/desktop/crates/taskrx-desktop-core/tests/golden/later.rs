//! `backlog`, `catch-up` and `refill`.

use serde_json::{Value, json};
use taskrx_desktop_core::backlog::{is_backlog, plan_backlog_completion};
use taskrx_desktop_core::catchup::{
    CATCH_UP_WINDOW_MS, catch_up_days, catch_up_slots, needs_catch_up,
};
use taskrx_desktop_core::refill::{DEFAULT_REFILL_REMINDER_DAYS, RefillProjection, project_refill};
use taskrx_desktop_core::timeline::TimelineRow;

use super::schedule::reminder;
use super::today::{row_from_json, row_json};
use super::{Outcome, field, int, text, unknown_call, zone};

pub fn backlog(input: &Value) -> Outcome {
    let r = reminder(field(input, "reminder")?)?;
    let completion = match plan_backlog_completion(&r, int(input, "completedAt")?) {
        Ok(c) => json!({
            "reminderId": c.reminder_id, "reminderKind": c.reminder_kind.wire(), "scheduledTime": c.scheduled_time,
            "status": c.status.wire(), "completedAt": c.completed_at,
        }),
        Err(_) => json!({ "refused": "invalidArgument" }),
    };
    Ok(json!({ "isBacklog": is_backlog(&r), "completion": completion }))
}

fn rows(input: &Value) -> Result<Vec<TimelineRow>, String> {
    field(input, "rows")?
        .as_array()
        .ok_or("rows are not a list")?
        .iter()
        .map(row_from_json)
        .collect()
}

fn listed(rows: &[TimelineRow]) -> Value {
    json!(rows.iter().map(row_json).collect::<Vec<_>>())
}

pub fn catch_up(input: &Value) -> Outcome {
    match text(input, "call")? {
        "constants" => Ok(json!({ "windowMs": CATCH_UP_WINDOW_MS })),
        "needsCatchUp" => Ok(listed(&needs_catch_up(&rows(input)?))),
        "catchUpDays" => Ok(json!(
            catch_up_days(&rows(input)?, &zone(input)?)
                .iter()
                .map(|d| json!({ "dayStart": d.day_start, "rows": listed(&d.rows) }))
                .collect::<Vec<_>>()
        )),
        "catchUpSlots" => Ok(json!(
            catch_up_slots(&rows(input)?)
                .iter()
                .map(|(t, slot)| json!({ "scheduledTime": t, "rows": listed(slot) }))
                .collect::<Vec<_>>()
        )),
        _ => unknown_call(input),
    }
}

pub fn refill(input: &Value) -> Outcome {
    if input.get("call").and_then(Value::as_str) == Some("constants") {
        return Ok(json!({ "defaultRefillReminderDays": DEFAULT_REFILL_REMINDER_DAYS }));
    }
    let medication = reminder(field(input, "medication")?)?;
    Ok(
        match project_refill(&medication, int(input, "now")?, &zone(input)?) {
            RefillProjection::Unprojectable(reason) => json!({ "unprojectable": reason.wire() }),
            RefillProjection::Outlook {
                occurrences_remaining,
                runs_out_at,
                days_remaining,
                warning_window_days,
                needs_refill,
            } => json!({
                "occurrencesRemaining": occurrences_remaining, "runsOutAt": runs_out_at, "daysRemaining": days_remaining,
                "warningWindowDays": warning_window_days, "needsRefill": needs_refill,
            }),
        },
    )
}
