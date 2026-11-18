//! `server-docs`: a Convex document read into its local row.

use serde_json::{Value, json};
use taskrx_desktop_core::domain::{notice_rules_to_json, overrides_to_json, pauses_to_json};
use taskrx_desktop_core::sync::server_docs::{
    self, PulledNotice, PulledReminder, PulledReminderInstance, PulledWatchedSource,
    instance_to_pulled_with, notice_to_pulled, parent_local_ids, reminder_to_pulled,
    watched_source_to_pulled,
};
use taskrx_desktop_core::sync::server_schedule::schedule_to_server;

use super::{Outcome, field, text, unknown_call};

fn first<T>(list: Vec<T>) -> Result<T, String> {
    list.into_iter()
        .next()
        .ok_or_else(|| "empty list".to_string())
}

fn one(doc: &Value) -> Value {
    Value::Array(vec![doc.clone()])
}

fn reminder_out(p: &PulledReminder) -> Value {
    let r = &p.reminder;
    json!({
        "convexId": p.convex_id,
        "rxcui": p.rxcui,
        "reminder": {
            "id": r.id, "userId": r.user_id, "kind": r.kind.wire(), "name": r.name,
            "dosage": r.dosage, "instructions": r.instructions,
            "schedule": schedule_to_server(&r.schedule),
            "priority": r.priority.wire(), "tags": r.tags,
            "pillsRemaining": r.pills_remaining, "pillsPerDose": r.pills_per_dose,
            "refillReminderDays": r.refill_reminder_days, "caregiverIds": r.caregiver_ids,
            "active": r.active, "createdAt": r.created_at, "updatedAt": r.updated_at,
            "pauses": pauses_to_json(&r.pauses), "overrides": overrides_to_json(&r.overrides),
            "noticeRules": notice_rules_to_json(&r.notice_rules),
        },
    })
}

fn instance_out(p: &PulledReminderInstance) -> Value {
    let i = &p.instance;
    json!({
        "convexId": p.convex_id,
        "instance": {
            "id": i.id, "reminderId": i.reminder_id, "userId": i.user_id, "reminderKind": i.reminder_kind.wire(),
            "scheduledTime": i.scheduled_time, "status": i.status.wire(), "completedAt": i.completed_at,
            "snoozedUntil": i.snoozed_until, "snoozeCount": i.snooze_count, "notes": i.notes,
            "createdAt": i.created_at, "updatedAt": i.updated_at,
        },
    })
}

fn notice_out(p: &PulledNotice) -> Value {
    let n = &p.notice;
    json!({
        "convexId": p.convex_id,
        "notice": {
            "id": n.id, "userId": n.user_id, "sourceKind": n.source_kind.wire(), "sourceValue": n.source_value,
            "title": n.title, "sentence": n.sentence, "receivedAt": n.received_at, "externalId": n.external_id,
            "resolution": n.resolution.wire(), "resolvedAt": n.resolved_at, "createdAt": n.created_at, "updatedAt": n.updated_at,
        },
    })
}

fn source_out(p: &PulledWatchedSource) -> Value {
    let s = &p.source;
    json!({
        "convexId": p.convex_id,
        "source": {
            "id": s.id, "userId": s.user_id, "kind": s.kind.wire(), "value": s.value, "label": s.label,
            "createdAt": s.created_at, "updatedAt": s.updated_at,
        },
    })
}

pub fn case(input: &Value) -> Outcome {
    let doc = field(input, "doc")?;
    let err = |e: serde_json::Error| e.to_string();
    match text(input, "call")? {
        "reminder" => Ok(reminder_to_pulled(&first(
            server_docs::reminders(&one(doc)).map_err(err)?,
        )?)
        .map_or(Value::Null, |p| reminder_out(&p))),
        "instance" => {
            let parents =
                parent_local_ids(&server_docs::reminders(field(input, "reminders")?).map_err(err)?);
            let d = first(server_docs::instances(&one(doc)).map_err(err)?)?;
            Ok(instance_to_pulled_with(&d, &parents).map_or(Value::Null, |p| instance_out(&p)))
        }
        "notice" => Ok(
            notice_to_pulled(&first(server_docs::notices(&one(doc)).map_err(err)?)?)
                .map_or(Value::Null, |p| notice_out(&p)),
        ),
        "watchSource" => Ok(watched_source_to_pulled(&first(
            server_docs::watch_sources(&one(doc)).map_err(err)?,
        )?)
        .map_or(Value::Null, |p| source_out(&p))),
        _ => unknown_call(input),
    }
}
