//! `parser`: a typed sentence read into a draft, and the draft corrected.

use serde_json::{Value, json};
use taskrx_desktop_core::domain::{Priority, ReminderKind, ScheduleTimes};
use taskrx_desktop_core::parser::{
    ReminderDraft, is_savable_draft, read_phrase, with_kind, with_name,
};
use taskrx_desktop_core::sync::server_schedule::{schedule_from_server, schedule_to_server};

use super::{Outcome, field, int, text, unknown_call};

fn draft_json(d: &ReminderDraft) -> Value {
    json!({
        "kind": d.kind.wire(), "name": d.name, "schedule": schedule_to_server(&d.schedule), "priority": d.priority.wire(),
        "dosage": d.dosage, "instructions": d.instructions, "pillsRemaining": d.pills_remaining, "pillsPerDose": d.pills_per_dose,
        "refillReminderDays": d.refill_reminder_days, "rxcui": d.rxcui, "unparsedTokens": d.unparsed_tokens,
        "isSavable": is_savable_draft(d),
    })
}

fn opt_text(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_string)
}

fn draft_from_json(v: &Value) -> Result<ReminderDraft, String> {
    Ok(ReminderDraft {
        kind: ReminderKind::from_wire(&opt_text(v, "kind").unwrap_or_default())
            .ok_or("a draft of an unknown kind")?,
        name: opt_text(v, "name").ok_or("a draft with no name")?,
        schedule: schedule_from_server(v.get("schedule")).unwrap_or(ScheduleTimes::None),
        priority: Priority::from_wire(&opt_text(v, "priority").unwrap_or_default())
            .ok_or("a draft of an unknown priority")?,
        dosage: opt_text(v, "dosage"),
        instructions: opt_text(v, "instructions"),
        pills_remaining: v.get("pillsRemaining").and_then(Value::as_f64),
        pills_per_dose: v.get("pillsPerDose").and_then(Value::as_f64),
        refill_reminder_days: v
            .get("refillReminderDays")
            .and_then(Value::as_f64)
            .map(|d| d as i32),
        rxcui: opt_text(v, "rxcui"),
        unparsed_tokens: v
            .get("unparsedTokens")
            .and_then(Value::as_array)
            .map(|a| {
                a.iter()
                    .filter_map(|t| t.as_str().map(str::to_string))
                    .collect()
            })
            .unwrap_or_default(),
    })
}

pub fn case(input: &Value) -> Outcome {
    match text(input, "call")? {
        "readPhrase" => {
            let reading = read_phrase(text(input, "phrase")?, int(input, "now")?);
            Ok(
                json!({ "understood": reading.understood, "reasons": reading.reasons, "draft": draft_json(&reading.draft) }),
            )
        }
        "withKind" => {
            let kind = ReminderKind::from_wire(text(input, "kind")?).ok_or("unknown kind")?;
            Ok(draft_json(&with_kind(
                &draft_from_json(field(input, "draft")?)?,
                kind,
            )))
        }
        "withName" => Ok(draft_json(&with_name(
            &draft_from_json(field(input, "draft")?)?,
            text(input, "name")?,
        ))),
        _ => unknown_call(input),
    }
}
