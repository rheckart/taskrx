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
use taskrx_desktop_core::domain::{InstanceStatus, allows_instance_transition, instance_id_for};
use taskrx_desktop_core::sync::push::{
    PushCall, PushRejected, instance_create_call, instance_update_call,
};

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
}
