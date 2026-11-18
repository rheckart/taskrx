//! What a write sends: the Convex function and its arguments for a queued payload.
//! Kotlin: `core/sync/Push.kt`. The Convex functions and their validators are the contract.

use serde_json::{Map, Value};

/// A value the Convex client sends. Its only number is a float64: every number in the schema
/// is `v.number()`, and an integer sent as an Int64 is refused.
#[derive(Debug, Clone, PartialEq)]
pub enum Wire {
    Null,
    Bool(bool),
    Number(f64),
    Text(String),
    List(Vec<Wire>),
    Object(Vec<(String, Wire)>),
}

impl Wire {
    /// A JSON value as the client sends it: every number becomes a float64.
    pub fn from_json(value: &Value) -> Wire {
        match value {
            Value::Null => Wire::Null,
            Value::Bool(b) => Wire::Bool(*b),
            Value::Number(n) => Wire::Number(n.as_f64().unwrap_or(f64::NAN)),
            Value::String(s) => Wire::Text(s.clone()),
            Value::Array(items) => Wire::List(items.iter().map(Wire::from_json).collect()),
            Value::Object(o) => Wire::Object(
                o.iter()
                    .map(|(k, v)| (k.clone(), Wire::from_json(v)))
                    .collect(),
            ),
        }
    }

    pub fn to_json(&self) -> Value {
        match self {
            Wire::Null => Value::Null,
            Wire::Bool(b) => Value::Bool(*b),
            Wire::Number(n) => serde_json::Number::from_f64(*n).map_or(Value::Null, Value::Number),
            Wire::Text(s) => Value::String(s.clone()),
            Wire::List(items) => Value::Array(items.iter().map(Wire::to_json).collect()),
            Wire::Object(fields) => Value::Object(
                fields
                    .iter()
                    .map(|(k, v)| (k.clone(), v.to_json()))
                    .collect::<Map<_, _>>(),
            ),
        }
    }
}

/// One Convex function call: `module:name` and its arguments.
#[derive(Debug, Clone, PartialEq)]
pub struct PushCall {
    pub function: &'static str,
    pub args: Vec<(String, Wire)>,
}

/// The payload cannot be turned into a call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PushRejected(pub String);

pub mod functions {
    pub const REMINDERS_CREATE: &str = "reminders:create";
    pub const REMINDERS_UPDATE: &str = "reminders:update";
    pub const REMINDERS_SOFT_DELETE: &str = "reminders:softDelete";
    pub const REMINDERS_LIST: &str = "reminders:list";
    pub const INSTANCES_CREATE: &str = "reminderInstances:create";
    pub const INSTANCES_MARK_COMPLETED: &str = "reminderInstances:markCompleted";
    pub const INSTANCES_SNOOZE: &str = "reminderInstances:snooze";
    pub const INSTANCES_MARK_SKIPPED: &str = "reminderInstances:markSkipped";
    pub const INSTANCES_MARK_MISSED: &str = "reminderInstances:markMissed";
    pub const INSTANCES_REOPEN: &str = "reminderInstances:reopen";
    pub const INSTANCES_LIST: &str = "reminderInstances:list";
    pub const NOTICES_CREATE: &str = "notices:create";
    pub const NOTICES_UPDATE: &str = "notices:update";
    pub const NOTICES_REMOVE: &str = "notices:remove";
    pub const NOTICES_LIST: &str = "notices:list";
    pub const WATCH_SOURCES_CREATE: &str = "watchSources:create";
    pub const WATCH_SOURCES_UPDATE: &str = "watchSources:update";
    pub const WATCH_SOURCES_REMOVE: &str = "watchSources:remove";
    pub const WATCH_SOURCES_LIST: &str = "watchSources:list";
}
use functions as f;

/// A field with a value: present and not null.
fn present<'a>(payload: &'a Value, key: &str) -> Option<&'a Value> {
    payload.get(key).filter(|v| !v.is_null())
}

struct Args(Vec<(String, Wire)>);

impl Args {
    fn new() -> Self {
        Args(Vec::new())
    }

    fn put(mut self, key: &str, value: Wire) -> Self {
        self.0.push((key.to_string(), value));
        self
    }

    fn required(mut self, payload: &Value, keys: &[&str]) -> Result<Self, PushRejected> {
        for key in keys {
            let value = present(payload, key)
                .ok_or_else(|| PushRejected(format!("Payload is missing {key}")))?;
            self.0.push((key.to_string(), Wire::from_json(value)));
        }
        Ok(self)
    }

    /// Optional fields are left out when the payload has no value: "leave the server's copy alone".
    fn optional(mut self, payload: &Value, keys: &[&str]) -> Self {
        for key in keys {
            if let Some(value) = present(payload, key) {
                self.0.push((key.to_string(), Wire::from_json(value)));
            }
        }
        self
    }
}

const OPTIONAL_REMINDER_FIELDS: &[&str] = &[
    "priority",
    "tags",
    "caregiverIds",
    "rxcui",
    "dosage",
    "instructions",
    "pillsRemaining",
    "pillsPerDose",
    "refillReminderDays",
    "pauses",
    "overrides",
    "noticeRules",
];

fn id(convex_id: &str) -> Wire {
    Wire::Text(convex_id.to_string())
}

/// `reminders.create`: an upsert on `localId` on the server.
pub fn reminder_create_call(payload: &Value) -> Result<PushCall, PushRejected> {
    let args = Args::new()
        .required(
            payload,
            &[
                "localId",
                "userId",
                "kind",
                "name",
                "schedule",
                "active",
                "createdAt",
                "updatedAt",
            ],
        )?
        .optional(payload, OPTIONAL_REMINDER_FIELDS);
    Ok(PushCall {
        function: f::REMINDERS_CREATE,
        args: args.0,
    })
}

pub fn reminder_update_call(payload: &Value, convex_id: &str) -> Result<PushCall, PushRejected> {
    let args = Args::new()
        .put("id", id(convex_id))
        .required(payload, &["name", "schedule", "active", "updatedAt"])?
        .optional(payload, OPTIONAL_REMINDER_FIELDS);
    Ok(PushCall {
        function: f::REMINDERS_UPDATE,
        args: args.0,
    })
}

pub fn reminder_delete_call(convex_id: &str) -> PushCall {
    PushCall {
        function: f::REMINDERS_SOFT_DELETE,
        args: vec![("id".into(), id(convex_id))],
    }
}

/// `reminderInstances.create`; the parent is named by its server id, which must already exist.
pub fn instance_create_call(
    payload: &Value,
    parent_convex_id: &str,
) -> Result<PushCall, PushRejected> {
    let args = Args::new()
        .required(
            payload,
            &[
                "localId",
                "userId",
                "scheduledTime",
                "status",
                "snoozeCount",
                "createdAt",
                "updatedAt",
            ],
        )?
        .put("reminderId", id(parent_convex_id))
        .optional(payload, &["completedAt", "snoozedUntil", "notes"]);
    Ok(PushCall {
        function: f::INSTANCES_CREATE,
        args: args.0,
    })
}

/// One of five mutations, by the status the row now has. `snooze` counts on the server, so two
/// offline devices both land; `pending` is `reopen`, the person's own undo (ADR 0008).
pub fn instance_update_call(payload: &Value, convex_id: &str) -> Result<PushCall, PushRejected> {
    let status = present(payload, "status")
        .ok_or_else(|| PushRejected("Payload is missing status".into()))?;
    let base = Args::new().put("id", id(convex_id));
    let (function, args) = match status.as_str() {
        Some("completed") => (
            f::INSTANCES_MARK_COMPLETED,
            base.optional(payload, &["completedAt"]),
        ),
        Some("snoozed") => {
            let until = present(payload, "snoozedUntil")
                .ok_or_else(|| PushRejected("Snoozed instance is missing snoozedUntil".into()))?;
            (
                f::INSTANCES_SNOOZE,
                base.put("snoozedUntil", Wire::from_json(until)),
            )
        }
        Some("skipped") => (
            f::INSTANCES_MARK_SKIPPED,
            base.optional(payload, &["notes"]),
        ),
        Some("missed") => (f::INSTANCES_MARK_MISSED, base),
        Some("pending") => (f::INSTANCES_REOPEN, base),
        _ => return Err(PushRejected(format!("Unknown instance status {status}"))),
    };
    Ok(PushCall {
        function,
        args: args.0,
    })
}

/// `notices.create`: an upsert on `localId`, as for reminders.
pub fn notice_create_call(payload: &Value) -> Result<PushCall, PushRejected> {
    let args = Args::new()
        .required(
            payload,
            &[
                "localId",
                "userId",
                "sourceKind",
                "sourceValue",
                "title",
                "receivedAt",
                "resolution",
                "createdAt",
                "updatedAt",
            ],
        )?
        .optional(payload, &["sentence", "externalId", "resolvedAt"]);
    Ok(PushCall {
        function: f::NOTICES_CREATE,
        args: args.0,
    })
}

/// `notices.update`: the resolution is the only thing a notice changes after it is stored.
pub fn notice_update_call(payload: &Value, convex_id: &str) -> Result<PushCall, PushRejected> {
    let args = Args::new()
        .put("id", id(convex_id))
        .required(payload, &["resolution", "updatedAt"])?
        .optional(payload, &["resolvedAt"]);
    Ok(PushCall {
        function: f::NOTICES_UPDATE,
        args: args.0,
    })
}

pub fn notice_delete_call(convex_id: &str) -> PushCall {
    PushCall {
        function: f::NOTICES_REMOVE,
        args: vec![("id".into(), id(convex_id))],
    }
}

pub fn watch_source_create_call(payload: &Value) -> Result<PushCall, PushRejected> {
    let args = Args::new().required(
        payload,
        &[
            "localId",
            "userId",
            "kind",
            "value",
            "label",
            "createdAt",
            "updatedAt",
        ],
    )?;
    Ok(PushCall {
        function: f::WATCH_SOURCES_CREATE,
        args: args.0,
    })
}

pub fn watch_source_update_call(
    payload: &Value,
    convex_id: &str,
) -> Result<PushCall, PushRejected> {
    let args = Args::new()
        .put("id", id(convex_id))
        .required(payload, &["label", "updatedAt"])?;
    Ok(PushCall {
        function: f::WATCH_SOURCES_UPDATE,
        args: args.0,
    })
}

pub fn watch_source_delete_call(convex_id: &str) -> PushCall {
    PushCall {
        function: f::WATCH_SOURCES_REMOVE,
        args: vec![("id".into(), id(convex_id))],
    }
}
