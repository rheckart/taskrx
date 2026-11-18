//! The documents Convex's `list` queries return, as a client delivers them, and the rows they
//! become. Kotlin: `core/sync/ServerDocs.kt`.
//!
//! Unknown fields are ignored. Every number is a float64 (a whole one is spelled
//! `1700000000000.0` or `1.7E12` as often as `1700000000000`), so each is read as `f64` and
//! narrowed explicitly in the mapping.

use std::collections::HashMap;

use serde::Deserialize;
use serde_json::Value;

use crate::domain::{
    InstanceStatus, Notice, NoticeResolution, Priority, Reminder, ReminderInstance, ReminderKind,
    ScheduleTimes, SourceKind, WatchedSource, notice_rules_from_json, overrides_from_json,
    pauses_from_json,
};
use crate::sync::server_schedule::schedule_from_server;

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerReminder {
    #[serde(rename = "_id")]
    pub id: String,
    pub local_id: String,
    pub user_id: String,
    pub kind: String,
    pub name: String,
    pub rxcui: Option<String>,
    pub dosage: Option<String>,
    pub instructions: Option<String>,
    pub schedule: Value,
    pub priority: String,
    #[serde(default)]
    pub tags: Vec<String>,
    pub pills_remaining: Option<f64>,
    pub pills_per_dose: Option<f64>,
    pub refill_reminder_days: Option<f64>,
    #[serde(default)]
    pub caregiver_ids: Vec<String>,
    pub active: bool,
    pub created_at: f64,
    pub updated_at: f64,
    pub deleted_at: Option<f64>,
    pub pauses: Option<Value>,
    pub overrides: Option<Value>,
    pub notice_rules: Option<Value>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerInstance {
    #[serde(rename = "_id")]
    pub id: String,
    pub local_id: String,
    pub user_id: String,
    /// The PARENT's server id, not its local id.
    pub reminder_id: String,
    pub reminder_kind: String,
    pub scheduled_time: f64,
    pub status: String,
    pub completed_at: Option<f64>,
    pub snoozed_until: Option<f64>,
    #[serde(default)]
    pub snooze_count: f64,
    pub notes: Option<String>,
    pub created_at: f64,
    pub updated_at: f64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerNotice {
    #[serde(rename = "_id")]
    pub id: String,
    pub local_id: String,
    pub user_id: String,
    pub source_kind: String,
    pub source_value: String,
    pub title: String,
    pub sentence: Option<String>,
    pub received_at: f64,
    pub external_id: Option<String>,
    pub resolution: String,
    pub resolved_at: Option<f64>,
    pub created_at: f64,
    pub updated_at: f64,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerWatchedSource {
    #[serde(rename = "_id")]
    pub id: String,
    pub local_id: String,
    pub user_id: String,
    pub kind: String,
    pub value: String,
    pub label: String,
    pub created_at: f64,
    pub updated_at: f64,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PulledReminder {
    pub reminder: Reminder,
    pub convex_id: String,
    pub rxcui: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PulledReminderInstance {
    pub instance: ReminderInstance,
    pub convex_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PulledNotice {
    pub notice: Notice,
    pub convex_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PulledWatchedSource {
    pub source: WatchedSource,
    pub convex_id: String,
}

/// A whole-millisecond reading of a float64, as Kotlin's `Double.toLong` (truncating, saturating).
fn ms(value: f64) -> i64 {
    value as i64
}

/// A list query's result. One malformed document fails the whole list, as the phone's decode does.
pub fn reminders(list: &Value) -> serde_json::Result<Vec<ServerReminder>> {
    Vec::deserialize(list)
}

pub fn instances(list: &Value) -> serde_json::Result<Vec<ServerInstance>> {
    Vec::deserialize(list)
}

pub fn notices(list: &Value) -> serde_json::Result<Vec<ServerNotice>> {
    Vec::deserialize(list)
}

pub fn watch_sources(list: &Value) -> serde_json::Result<Vec<ServerWatchedSource>> {
    Vec::deserialize(list)
}

/// The row, or `None` if the document names a Kind this build does not know. A schedule that
/// cannot be read becomes `none` (the Backlog, where it can be repaired); an unknown priority, normal.
pub fn reminder_to_pulled(doc: &ServerReminder) -> Option<PulledReminder> {
    let kind = ReminderKind::from_wire(&doc.kind)?;
    Some(PulledReminder {
        reminder: Reminder {
            id: doc.local_id.clone(),
            user_id: doc.user_id.clone(),
            kind,
            name: doc.name.clone(),
            dosage: doc.dosage.clone(),
            instructions: doc.instructions.clone(),
            schedule: schedule_from_server(Some(&doc.schedule)).unwrap_or(ScheduleTimes::None),
            priority: Priority::from_wire(&doc.priority).unwrap_or(Priority::Normal),
            tags: doc.tags.clone(),
            pills_remaining: doc.pills_remaining,
            pills_per_dose: doc.pills_per_dose,
            refill_reminder_days: doc.refill_reminder_days.map(|d| d as i32),
            caregiver_ids: doc.caregiver_ids.clone(),
            active: doc.active,
            created_at: ms(doc.created_at),
            updated_at: ms(doc.updated_at),
            pauses: pauses_from_json(doc.pauses.as_ref()),
            overrides: overrides_from_json(doc.overrides.as_ref()),
            notice_rules: notice_rules_from_json(doc.notice_rules.as_ref()),
        },
        convex_id: doc.id.clone(),
        rxcui: doc.rxcui.clone(),
    })
}

/// Each server reminder id to the reminder's local id: an instance names its parent by the
/// server id, and the rows the rules read join on the local one.
pub fn parent_local_ids(reminders: &[ServerReminder]) -> HashMap<String, String> {
    reminders
        .iter()
        .map(|r| (r.id.clone(), r.local_id.clone()))
        .collect()
}

/// The row with `local_reminder_id` as its parent, or `None` for a kind or status this build does not know.
pub fn instance_to_pulled(
    doc: &ServerInstance,
    local_reminder_id: &str,
) -> Option<PulledReminderInstance> {
    let kind = ReminderKind::from_wire(&doc.reminder_kind)?;
    let status = InstanceStatus::from_wire(&doc.status)?;
    Some(PulledReminderInstance {
        instance: ReminderInstance {
            id: doc.local_id.clone(),
            reminder_id: local_reminder_id.to_string(),
            user_id: doc.user_id.clone(),
            reminder_kind: kind,
            scheduled_time: ms(doc.scheduled_time),
            status,
            completed_at: doc.completed_at.map(ms),
            snoozed_until: doc.snoozed_until.map(ms),
            snooze_count: doc.snooze_count as i32,
            notes: doc.notes.clone(),
            created_at: ms(doc.created_at),
            updated_at: ms(doc.updated_at),
        },
        convex_id: doc.id.clone(),
    })
}

/// The row, its parent looked up in `parents` ([`parent_local_ids`]); `None` when the parent
/// was not pulled or [`instance_to_pulled`] refuses it.
pub fn instance_to_pulled_with(
    doc: &ServerInstance,
    parents: &HashMap<String, String>,
) -> Option<PulledReminderInstance> {
    instance_to_pulled(doc, parents.get(&doc.reminder_id)?)
}

pub fn notice_to_pulled(doc: &ServerNotice) -> Option<PulledNotice> {
    Some(PulledNotice {
        notice: Notice {
            id: doc.local_id.clone(),
            user_id: doc.user_id.clone(),
            source_kind: SourceKind::from_wire(&doc.source_kind)?,
            source_value: doc.source_value.clone(),
            title: doc.title.clone(),
            sentence: doc.sentence.clone(),
            received_at: ms(doc.received_at),
            external_id: doc.external_id.clone(),
            resolution: NoticeResolution::from_wire(&doc.resolution)?,
            resolved_at: doc.resolved_at.map(ms),
            created_at: ms(doc.created_at),
            updated_at: ms(doc.updated_at),
        },
        convex_id: doc.id.clone(),
    })
}

pub fn watched_source_to_pulled(doc: &ServerWatchedSource) -> Option<PulledWatchedSource> {
    Some(PulledWatchedSource {
        source: WatchedSource {
            id: doc.local_id.clone(),
            user_id: doc.user_id.clone(),
            kind: SourceKind::from_wire(&doc.kind)?,
            value: doc.value.clone(),
            label: doc.label.clone(),
            created_at: ms(doc.created_at),
            updated_at: ms(doc.updated_at),
        },
        convex_id: doc.id.clone(),
    })
}
