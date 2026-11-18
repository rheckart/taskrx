//! `domain`: the lifecycles, pauses and moves.

use serde_json::{Value, json};
use taskrx_desktop_core::domain::{
    InstanceAction, InstanceStatus, OccurrenceOverride, ReminderInstance, ReminderPhase,
    action_target, add_pause, allows_instance_transition, available_actions, can_pause,
    is_paused_at, live_pause, move_refusal, overrides_from_json, overrides_to_json,
    pauses_from_json, pauses_to_json, reminder_actions, reminder_phase, resume_pause,
    with_override, without_override,
};
use taskrx_desktop_core::sync::server_docs::{self, instance_to_pulled};

use super::schedule::reminder;
use super::{Outcome, field, int, text, unknown_call};

fn status(input: &Value, key: &str) -> Result<InstanceStatus, String> {
    let wire = text(input, key)?;
    InstanceStatus::from_wire(wire).ok_or_else(|| format!("unknown status {wire}"))
}

fn optional_status(input: &Value, key: &str) -> Result<Option<InstanceStatus>, String> {
    match field(input, key)? {
        Value::Null => Ok(None),
        _ => status(input, key).map(Some),
    }
}

/// Instance documents whose `reminderId` is already the parent's local id.
pub fn instances(list: &Value) -> Result<Vec<ReminderInstance>, String> {
    server_docs::instances(list)
        .map_err(|e| e.to_string())?
        .iter()
        .map(|d| {
            instance_to_pulled(d, &d.reminder_id)
                .map(|p| p.instance)
                .ok_or_else(|| "an instance of an unknown kind or status".to_string())
        })
        .collect()
}

fn pauses(input: &Value) -> Result<Vec<taskrx_desktop_core::domain::PauseWindow>, String> {
    Ok(pauses_from_json(Some(field(input, "pauses")?)))
}

fn overrides(value: &Value) -> Vec<OccurrenceOverride> {
    overrides_from_json(Some(value))
}

/// A set as the fixtures write one: sorted, since it has no order of its own.
fn sorted<'a>(items: impl IntoIterator<Item = &'a str>) -> Value {
    let mut v: Vec<&str> = items.into_iter().collect();
    v.sort_unstable();
    json!(v)
}

fn phase(p: ReminderPhase) -> Value {
    match p {
        ReminderPhase::Active => json!({ "phase": "active" }),
        ReminderPhase::Stopped => json!({ "phase": "stopped" }),
        ReminderPhase::Paused(w) => json!({ "phase": "paused", "from": w.from, "until": w.until }),
    }
}

pub fn case(input: &Value) -> Outcome {
    match text(input, "call")? {
        "availableActions" => Ok(sorted(
            available_actions(status(input, "status")?)
                .iter()
                .map(|a| a.wire()),
        )),
        "actionTarget" => {
            let action =
                InstanceAction::from_wire(text(input, "action")?).ok_or("unknown action")?;
            Ok(action_target(action, status(input, "from")?)
                .map_or(Value::Null, |s| json!(s.wire())))
        }
        "allowsInstanceTransition" => Ok(json!(allows_instance_transition(
            optional_status(input, "from")?,
            status(input, "to")?
        ))),
        "reminderLifecycle" => {
            let r = reminder(field(input, "reminder")?)?;
            let p = reminder_phase(&r, int(input, "now")?);
            Ok(json!({
                "phase": phase(p),
                "canPause": can_pause(&r),
                "actions": sorted(reminder_actions(&r, p).iter().map(|a| a.wire())),
            }))
        }
        "isPausedAt" => Ok(json!(is_paused_at(&pauses(input)?, int(input, "instant")?))),
        "livePause" => Ok(live_pause(&pauses(input)?, int(input, "now")?)
            .map_or(Value::Null, |w| pauses_to_json(&[w])[0].clone())),
        "addPause" => {
            let until = match field(input, "until")? {
                Value::Null => None,
                _ => Some(int(input, "until")?),
            };
            Ok(
                match add_pause(
                    &pauses(input)?,
                    int(input, "from")?,
                    until,
                    int(input, "now")?,
                ) {
                    Ok(w) => pauses_to_json(&w),
                    Err(_) => json!({ "refused": "invalidArgument" }),
                },
            )
        }
        "resumePause" => Ok(pauses_to_json(&resume_pause(
            &pauses(input)?,
            int(input, "now")?,
        ))),
        "pausesFromJson" => Ok(pauses_to_json(&pauses(input)?)),
        "moveRefusal" => {
            let r = reminder(field(input, "reminder")?)?;
            let minted = instances(field(input, "instances")?)?;
            Ok(move_refusal(
                &r,
                int(input, "from")?,
                int(input, "to")?,
                int(input, "now")?,
                &minted,
            )
            .map_or(Value::Null, |m| json!(m.wire())))
        }
        "withOverride" => {
            let added = overrides(&Value::Array(vec![field(input, "override")?.clone()]));
            let added = added.into_iter().next().ok_or("an unreadable override")?;
            Ok(overrides_to_json(&with_override(
                &overrides(field(input, "overrides")?),
                added,
            )))
        }
        "withoutOverride" => Ok(overrides_to_json(&without_override(
            &overrides(field(input, "overrides")?),
            int(input, "from")?,
        ))),
        "overridesFromJson" => Ok(overrides_to_json(&overrides(field(input, "overrides")?))),
        _ => unknown_call(input),
    }
}
