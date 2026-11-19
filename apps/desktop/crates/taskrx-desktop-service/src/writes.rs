//! What the desktop sends Convex when the person answers. The call shapes are the phone's
//! (`taskrx_desktop_core::sync::push`, held to the `push` fixtures); this decides which one.
//!
//! - A moment with an Instance on the server gets the status call for its new status
//!   (`markCompleted`, `snooze`, `markSkipped`, `reopen`).
//! - A moment with none gets one `reminderInstances.create` carrying the answer, under the id
//!   derived from the moment (ADR 0018), so it meets the phone's row for the same moment. The
//!   server spends supply on a completion either way.
//! - The desktop never records Missed (ADR 0005, 0016): [`Answer`] has no such case.

use serde_json::{Map, Value, json};
use taskrx_desktop_core::domain::{
    InstanceStatus, NoticeResolution, NoticeRule, OccurrenceOverride, PauseWindow, Priority,
    Reminder, ReminderAction, ReminderKind, ScheduleTimes, add_pause, allows_instance_transition,
    instance_id_for, notice_rules_to_json, overrides_to_json, pauses_to_json, reminder_actions,
    reminder_phase, resume_pause,
};
use taskrx_desktop_core::parser::ReminderDraft;
use taskrx_desktop_core::sync::push::{
    PushCall, PushRejected, instance_create_call, instance_update_call, notice_update_call,
    reminder_create_call, reminder_update_call,
};
use taskrx_desktop_core::sync::server_schedule::schedule_to_server;

use crate::data::Data;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Answer {
    /// Taken (a medication) or done (a task), at this instant.
    Take {
        at: i64,
    },
    Skip,
    Snooze {
        until: i64,
    },
    /// Takes an answer back: the person's own undo.
    Reopen,
}

impl Answer {
    fn status(self) -> InstanceStatus {
        match self {
            Answer::Take { .. } => InstanceStatus::Completed,
            Answer::Skip => InstanceStatus::Skipped,
            Answer::Snooze { .. } => InstanceStatus::Snoozed,
            Answer::Reopen => InstanceStatus::Pending,
        }
    }
}

/// The moment answered: a row of a screen.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Moment<'a> {
    pub reminder_id: &'a str,
    pub scheduled_time: i64,
    /// The Instance the row shows, if one is recorded.
    pub instance_id: Option<&'a str>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Refusal {
    /// The lifecycle does not allow it from the row's status (a completed dose is not snoozed).
    NotAllowed,
    /// Nothing is recorded for the moment, so there is nothing to take back.
    NothingToReopen,
    /// The Reminder or Instance is not on the server as this client last saw it.
    NotOnServer,
    Rejected(String),
}

impl From<PushRejected> for Refusal {
    fn from(e: PushRejected) -> Self {
        Refusal::Rejected(e.0)
    }
}

fn put(payload: &mut Map<String, Value>, answer: Answer) {
    payload.insert("status".into(), json!(answer.status().wire()));
    match answer {
        Answer::Take { at } => {
            payload.insert("completedAt".into(), json!(at));
        }
        Answer::Snooze { until } => {
            payload.insert("snoozedUntil".into(), json!(until));
        }
        Answer::Skip | Answer::Reopen => {}
    }
}

pub fn answer_call(
    data: &Data,
    user_id: &str,
    moment: &Moment,
    answer: Answer,
    now: i64,
) -> Result<PushCall, Refusal> {
    let to = answer.status();
    if let Some(local_id) = moment.instance_id {
        let instance = data.instance(local_id).ok_or(Refusal::NotOnServer)?;
        let server_id = data
            .instance_server_id(local_id)
            .ok_or(Refusal::NotOnServer)?;
        if !allows_instance_transition(Some(instance.status), to) {
            return Err(Refusal::NotAllowed);
        }
        let mut payload = Map::new();
        put(&mut payload, answer);
        return Ok(instance_update_call(&Value::Object(payload), server_id)?);
    }

    if answer == Answer::Reopen {
        return Err(Refusal::NothingToReopen);
    }
    let parent = data
        .reminder_server_id(moment.reminder_id)
        .ok_or(Refusal::NotOnServer)?;
    let mut payload = Map::new();
    payload.insert(
        "localId".into(),
        json!(instance_id_for(moment.reminder_id, moment.scheduled_time)),
    );
    payload.insert("userId".into(), json!(user_id));
    payload.insert("scheduledTime".into(), json!(moment.scheduled_time));
    payload.insert(
        "snoozeCount".into(),
        json!(i32::from(matches!(answer, Answer::Snooze { .. }))),
    );
    payload.insert("createdAt".into(), json!(now));
    payload.insert("updatedAt".into(), json!(now));
    put(&mut payload, answer);
    Ok(instance_create_call(&Value::Object(payload), parent)?)
}

/// Takes back a record whose moment is the act itself (a Backlog item's): reopened, then
/// skipped, the second once the first has landed. Reopened alone it would be a pending dose at a
/// past moment, which the screens read as overdue; skipped, it reads as not done. The server
/// keeps every Instance, so this is how a record made by mistake is undone.
pub fn take_back_record(data: &Data, instance_id: &str) -> Result<(PushCall, PushCall), Refusal> {
    let instance = data.instance(instance_id).ok_or(Refusal::NotOnServer)?;
    if instance.status != InstanceStatus::Completed {
        return Err(Refusal::NothingToReopen);
    }
    let server_id = data
        .instance_server_id(instance_id)
        .ok_or(Refusal::NotOnServer)?;
    let to = |status: InstanceStatus| {
        instance_update_call(&json!({ "status": status.wire() }), server_id)
    };
    Ok((to(InstanceStatus::Pending)?, to(InstanceStatus::Skipped)?))
}

/// What an edit of a Reminder means to change. A field left `None` is not sent, so the server's
/// copy stays: an unrelated edit must not overwrite a pause, a moved moment or a pill count
/// another device set (the phone's `RemindersRepository.update`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ReminderPatch {
    pub name: Option<String>,
    pub dosage: Option<String>,
    pub instructions: Option<String>,
    pub schedule: Option<ScheduleTimes>,
    pub active: Option<bool>,
    /// Only a count the person typed: a dose's spend is the server's (ADR 0018).
    pub pills_remaining: Option<f64>,
    pub pauses: Option<Vec<PauseWindow>>,
    pub overrides: Option<Vec<OccurrenceOverride>>,
    pub notice_rules: Option<Vec<NoticeRule>>,
}

fn put_opt(payload: &mut Map<String, Value>, key: &str, value: Option<Value>) {
    payload.insert(key.into(), value.unwrap_or(Value::Null));
}

/// `reminders.update` for one Reminder: the fields it keeps, with the patch over them.
pub fn reminder_patch_call(
    data: &Data,
    reminder_id: &str,
    patch: &ReminderPatch,
    now: i64,
) -> Result<PushCall, Refusal> {
    let r: &Reminder = data.reminder(reminder_id).ok_or(Refusal::NotOnServer)?;
    let server_id = data
        .reminder_server_id(reminder_id)
        .ok_or(Refusal::NotOnServer)?;
    let mut p = Map::new();
    p.insert("localId".into(), json!(r.id));
    p.insert("userId".into(), json!(r.user_id));
    p.insert("kind".into(), json!(r.kind.wire()));
    p.insert(
        "name".into(),
        json!(patch.name.clone().unwrap_or_else(|| r.name.clone())),
    );
    put_opt(
        &mut p,
        "rxcui",
        data.reminder_rxcui(reminder_id).map(|x| json!(x)),
    );
    put_opt(
        &mut p,
        "dosage",
        patch
            .dosage
            .clone()
            .or_else(|| r.dosage.clone())
            .map(Value::from),
    );
    put_opt(
        &mut p,
        "instructions",
        patch
            .instructions
            .clone()
            .or_else(|| r.instructions.clone())
            .map(Value::from),
    );
    p.insert(
        "schedule".into(),
        schedule_to_server(patch.schedule.as_ref().unwrap_or(&r.schedule)),
    );
    // Priority, tags and caregivers are the server's unless the person chose them here.
    p.insert("priority".into(), Value::Null);
    p.insert("tags".into(), Value::Null);
    p.insert("caregiverIds".into(), Value::Null);
    put_opt(
        &mut p,
        "pillsRemaining",
        patch.pills_remaining.map(Value::from),
    );
    put_opt(&mut p, "pillsPerDose", r.pills_per_dose.map(Value::from));
    put_opt(
        &mut p,
        "refillReminderDays",
        r.refill_reminder_days.map(Value::from),
    );
    p.insert("active".into(), json!(patch.active.unwrap_or(r.active)));
    p.insert("createdAt".into(), json!(r.created_at));
    p.insert("updatedAt".into(), json!(now));
    put_opt(
        &mut p,
        "pauses",
        patch.pauses.as_deref().map(pauses_to_json),
    );
    put_opt(
        &mut p,
        "overrides",
        patch.overrides.as_deref().map(overrides_to_json),
    );
    put_opt(
        &mut p,
        "noticeRules",
        patch.notice_rules.as_deref().map(notice_rules_to_json),
    );
    Ok(reminder_update_call(&Value::Object(p), server_id)?)
}

/// A new Reminder, as the phone's `RemindersRepository.create` sends one.
#[derive(Debug, Clone, PartialEq)]
pub struct NewReminder {
    pub kind: ReminderKind,
    pub name: String,
    pub dosage: Option<String>,
    pub instructions: Option<String>,
    pub schedule: ScheduleTimes,
    pub priority: Option<Priority>,
    pub rxcui: Option<String>,
    pub pills_remaining: Option<f64>,
    pub pills_per_dose: Option<f64>,
    pub refill_reminder_days: Option<i32>,
}

impl NewReminder {
    /// What the phrase parser read, trimmed at the boundary as the phone's Add screen does.
    pub fn from_draft(draft: &ReminderDraft) -> NewReminder {
        let trimmed = |s: &Option<String>| {
            s.as_deref()
                .map(str::trim)
                .filter(|t| !t.is_empty())
                .map(str::to_string)
        };
        NewReminder {
            kind: draft.kind,
            name: draft.name.trim().to_string(),
            dosage: trimmed(&draft.dosage),
            instructions: trimmed(&draft.instructions),
            schedule: draft.schedule.clone(),
            priority: Some(draft.priority),
            rxcui: draft.rxcui.clone(),
            pills_remaining: draft.pills_remaining,
            pills_per_dose: draft.pills_per_dose,
            refill_reminder_days: draft.refill_reminder_days,
        }
    }

    /// A one-off task, as a notice makes one.
    pub fn task(name: String, instructions: Option<String>, at: i64) -> NewReminder {
        NewReminder {
            kind: ReminderKind::Task,
            name,
            dosage: None,
            instructions,
            schedule: ScheduleTimes::Once { at },
            priority: None,
            rxcui: None,
            pills_remaining: None,
            pills_per_dose: None,
            refill_reminder_days: None,
        }
    }
}

/// `reminders.create` under a new random local id, which is returned with the call.
pub fn reminder_create(
    user_id: &str,
    new: &NewReminder,
    now: i64,
) -> Result<(String, PushCall), Refusal> {
    let id = uuid::Uuid::new_v4().to_string();
    let mut p = Map::new();
    p.insert("localId".into(), json!(id));
    p.insert("userId".into(), json!(user_id));
    p.insert("kind".into(), json!(new.kind.wire()));
    p.insert("name".into(), json!(new.name));
    put_opt(&mut p, "rxcui", new.rxcui.clone().map(Value::from));
    put_opt(&mut p, "dosage", new.dosage.clone().map(Value::from));
    put_opt(
        &mut p,
        "instructions",
        new.instructions.clone().map(Value::from),
    );
    p.insert("schedule".into(), schedule_to_server(&new.schedule));
    put_opt(&mut p, "priority", new.priority.map(|x| json!(x.wire())));
    put_opt(
        &mut p,
        "pillsRemaining",
        new.pills_remaining.map(Value::from),
    );
    put_opt(&mut p, "pillsPerDose", new.pills_per_dose.map(Value::from));
    put_opt(
        &mut p,
        "refillReminderDays",
        new.refill_reminder_days.map(Value::from),
    );
    for key in ["tags", "caregiverIds", "pauses", "overrides", "noticeRules"] {
        p.insert(key.into(), Value::Null);
    }
    p.insert("active".into(), json!(true));
    p.insert("createdAt".into(), json!(now));
    p.insert("updatedAt".into(), json!(now));
    Ok((id, reminder_create_call(&Value::Object(p))?))
}

/// The edit Pause, Resume or Stop makes. A pause from the desktop runs until resumed.
pub fn reminder_action_patch(
    reminder: &Reminder,
    action: ReminderAction,
    now: i64,
) -> Result<ReminderPatch, Refusal> {
    if !reminder_actions(reminder, reminder_phase(reminder, now)).contains(&action) {
        return Err(Refusal::NotAllowed);
    }
    Ok(match action {
        ReminderAction::Pause => ReminderPatch {
            pauses: Some(
                add_pause(&reminder.pauses, now, None, now).map_err(|_| Refusal::NotAllowed)?,
            ),
            ..ReminderPatch::default()
        },
        ReminderAction::Resume => ReminderPatch {
            pauses: Some(resume_pause(&reminder.pauses, now)),
            ..ReminderPatch::default()
        },
        ReminderAction::Stop => ReminderPatch {
            active: Some(false),
            ..ReminderPatch::default()
        },
    })
}

/// `notices.update`: the decision the person made about a notice.
pub fn notice_resolve_call(
    data: &Data,
    notice_id: &str,
    resolution: NoticeResolution,
    now: i64,
) -> Result<PushCall, Refusal> {
    let server_id = data
        .notice_server_id(notice_id)
        .ok_or(Refusal::NotOnServer)?;
    let payload = json!({
        "resolution": resolution.wire(),
        "resolvedAt": now,
        "updatedAt": now,
    });
    Ok(notice_update_call(&payload, server_id)?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data::Lists;
    use taskrx_desktop_core::sync::push::{Wire, functions};

    const AT: i64 = 1_780_000_000_000;

    fn data() -> Data {
        let reminder = json!({
            "_id": "srv_r1", "localId": "r1", "userId": "user_1", "kind": "medication",
            "name": "Flonase", "schedule": { "type": "daily", "times": ["09:00"] },
            "priority": "normal", "active": true, "createdAt": 1.0, "updatedAt": 1.0
        });
        let instance = json!({
            "_id": "srv_i1", "localId": "i1", "userId": "user_1", "reminderId": "srv_r1",
            "reminderKind": "medication", "scheduledTime": AT as f64, "status": "completed",
            "completedAt": AT as f64, "snoozeCount": 0.0, "createdAt": 1.0, "updatedAt": 2.0
        });
        Data::decode(&Lists {
            reminders: Some(json!([reminder])),
            instances: Some(json!([instance])),
            ..Lists::default()
        })
        .unwrap()
    }

    fn arg<'a>(call: &'a PushCall, key: &str) -> Option<&'a Wire> {
        call.args.iter().find(|(k, _)| k == key).map(|(_, v)| v)
    }

    fn fresh(answer: Answer) -> Result<PushCall, Refusal> {
        let moment = Moment {
            reminder_id: "r1",
            scheduled_time: AT + 86_400_000,
            instance_id: None,
        };
        answer_call(&data(), "user_1", &moment, answer, AT + 90_000_000)
    }

    #[test]
    fn a_record_is_taken_back_as_reopened_then_skipped() {
        let (first, then) = take_back_record(&data(), "i1").unwrap();
        assert_eq!(first.function, functions::INSTANCES_REOPEN);
        assert_eq!(then.function, functions::INSTANCES_MARK_SKIPPED);
        assert_eq!(arg(&then, "id"), Some(&Wire::Text("srv_i1".into())));
        assert_eq!(take_back_record(&data(), "i9"), Err(Refusal::NotOnServer));
    }

    #[test]
    fn only_a_completed_record_is_taken_back() {
        let mut d = data();
        d.instances[0].status = InstanceStatus::Skipped;
        assert_eq!(take_back_record(&d, "i1"), Err(Refusal::NothingToReopen));
    }

    #[test]
    fn a_moment_with_no_row_is_created_with_its_answer_under_the_derived_id() {
        let call = fresh(Answer::Take {
            at: AT + 86_460_000,
        })
        .unwrap();
        assert_eq!(call.function, functions::INSTANCES_CREATE);
        assert_eq!(
            arg(&call, "localId"),
            Some(&Wire::Text(instance_id_for("r1", AT + 86_400_000)))
        );
        assert_eq!(arg(&call, "reminderId"), Some(&Wire::Text("srv_r1".into())));
        assert_eq!(arg(&call, "status"), Some(&Wire::Text("completed".into())));
        assert_eq!(
            arg(&call, "completedAt"),
            Some(&Wire::Number((AT + 86_460_000) as f64))
        );
        assert_eq!(arg(&call, "snoozeCount"), Some(&Wire::Number(0.0)));
        // An answer is never a fresh pending mint, so the server does not refuse it as one.
        assert_ne!(arg(&call, "status"), Some(&Wire::Text("pending".into())));
    }

    #[test]
    fn a_snooze_with_no_row_counts_one() {
        let call = fresh(Answer::Snooze {
            until: AT + 86_700_000,
        })
        .unwrap();
        assert_eq!(arg(&call, "status"), Some(&Wire::Text("snoozed".into())));
        assert_eq!(arg(&call, "snoozeCount"), Some(&Wire::Number(1.0)));
        assert_eq!(
            arg(&call, "snoozedUntil"),
            Some(&Wire::Number((AT + 86_700_000) as f64))
        );
    }

    #[test]
    fn nothing_recorded_cannot_be_reopened() {
        assert_eq!(fresh(Answer::Reopen), Err(Refusal::NothingToReopen));
    }

    #[test]
    fn a_recorded_row_gets_its_status_call() {
        let moment = Moment {
            reminder_id: "r1",
            scheduled_time: AT,
            instance_id: Some("i1"),
        };
        let reopen = answer_call(&data(), "user_1", &moment, Answer::Reopen, AT).unwrap();
        assert_eq!(reopen.function, functions::INSTANCES_REOPEN);
        assert_eq!(
            reopen.args,
            vec![("id".into(), Wire::Text("srv_i1".into()))]
        );
        // A completed dose cannot be snoozed or skipped; it is reopened first.
        assert_eq!(
            answer_call(&data(), "user_1", &moment, Answer::Skip, AT),
            Err(Refusal::NotAllowed)
        );
    }

    #[test]
    fn an_unknown_row_or_parent_is_not_written() {
        let moment = Moment {
            reminder_id: "r9",
            scheduled_time: AT,
            instance_id: None,
        };
        assert_eq!(
            answer_call(&data(), "user_1", &moment, Answer::Skip, AT),
            Err(Refusal::NotOnServer)
        );
        let moment = Moment {
            instance_id: Some("i9"),
            ..moment
        };
        assert_eq!(
            answer_call(&data(), "user_1", &moment, Answer::Skip, AT),
            Err(Refusal::NotOnServer)
        );
    }

    #[test]
    fn a_patch_sends_only_what_it_changes() {
        let moved = OccurrenceOverride {
            from: AT,
            to: AT + 3_600_000,
            notice_id: Some("n1".into()),
        };
        let call = reminder_patch_call(
            &data(),
            "r1",
            &ReminderPatch {
                overrides: Some(vec![moved]),
                ..ReminderPatch::default()
            },
            AT + 10,
        )
        .unwrap();
        assert_eq!(call.function, functions::REMINDERS_UPDATE);
        assert_eq!(arg(&call, "id"), Some(&Wire::Text("srv_r1".into())));
        assert_eq!(arg(&call, "name"), Some(&Wire::Text("Flonase".into())));
        assert_eq!(
            arg(&call, "updatedAt"),
            Some(&Wire::Number((AT + 10) as f64))
        );
        assert!(arg(&call, "overrides").is_some());
        for untouched in [
            "pauses",
            "noticeRules",
            "pillsRemaining",
            "priority",
            "tags",
        ] {
            assert_eq!(arg(&call, untouched), None, "{untouched} was sent");
        }
    }

    #[test]
    fn a_new_reminder_gets_a_fresh_id_each_time() {
        let new = NewReminder::task(
            "Pick up the prescription".into(),
            Some("Ready Thursday".into()),
            AT,
        );
        let (a, call) = reminder_create("user_1", &new, AT).unwrap();
        let (b, _) = reminder_create("user_1", &new, AT).unwrap();
        assert_ne!(a, b);
        assert_eq!(call.function, functions::REMINDERS_CREATE);
        assert_eq!(arg(&call, "localId"), Some(&Wire::Text(a)));
        assert_eq!(arg(&call, "kind"), Some(&Wire::Text("task".into())));
        assert_eq!(arg(&call, "active"), Some(&Wire::Bool(true)));
    }

    #[test]
    fn pause_resume_and_stop_are_offered_by_the_phase() {
        let d = data();
        let r = d.reminder("r1").unwrap();
        let paused = reminder_action_patch(r, ReminderAction::Pause, AT).unwrap();
        assert_eq!(
            paused.pauses,
            Some(vec![PauseWindow {
                from: AT,
                until: None
            }])
        );
        // Not paused, so nothing to resume.
        assert_eq!(
            reminder_action_patch(r, ReminderAction::Resume, AT),
            Err(Refusal::NotAllowed)
        );
        let stop = reminder_action_patch(r, ReminderAction::Stop, AT).unwrap();
        assert_eq!(stop.active, Some(false));
        let call = reminder_patch_call(&d, "r1", &stop, AT).unwrap();
        assert_eq!(arg(&call, "active"), Some(&Wire::Bool(false)));
    }

    #[test]
    fn a_read_phrase_becomes_a_new_reminder() {
        let reading = taskrx_desktop_core::parser::read_phrase("Lisinopril 10mg daily at 8am", AT);
        let new = NewReminder::from_draft(&reading.draft);
        assert_eq!(new.kind, ReminderKind::Medication);
        let (_, call) = reminder_create("user_1", &new, AT).unwrap();
        assert_eq!(arg(&call, "name"), Some(&Wire::Text(new.name.clone())));
        assert!(arg(&call, "schedule").is_some());
    }

    #[test]
    fn an_unknown_notice_is_not_resolved() {
        assert_eq!(
            notice_resolve_call(&data(), "n9", NoticeResolution::Dismissed, AT),
            Err(Refusal::NotOnServer)
        );
    }
}
