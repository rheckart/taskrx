//! Reminders, Instances, Notices and the lists a Reminder carries, with their JSON shapes.
//! Kotlin: `core/domain/Reminder.kt`, `Pause.kt`, `OccurrenceOverride.kt`, `Notice.kt`.

use serde_json::{Map, Value, json};

use super::enums::{InstanceStatus, NoticeResolution, Priority, ReminderKind, SourceKind};
use super::schedule::ScheduleTimes;

#[derive(Debug, Clone, PartialEq)]
pub struct Reminder {
    pub id: String,
    pub user_id: String,
    pub kind: ReminderKind,
    pub name: String,
    pub dosage: Option<String>,
    pub instructions: Option<String>,
    pub schedule: ScheduleTimes,
    pub priority: Priority,
    pub tags: Vec<String>,
    pub pills_remaining: Option<f64>,
    pub pills_per_dose: Option<f64>,
    pub refill_reminder_days: Option<i32>,
    pub caregiver_ids: Vec<String>,
    pub active: bool,
    pub created_at: i64,
    pub updated_at: i64,
    pub pauses: Vec<PauseWindow>,
    pub overrides: Vec<OccurrenceOverride>,
    pub notice_rules: Vec<NoticeRule>,
}

/// The record of what became of one Occurrence. `reminder_id` is the parent's LOCAL id.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReminderInstance {
    pub id: String,
    pub reminder_id: String,
    pub user_id: String,
    pub reminder_kind: ReminderKind,
    pub scheduled_time: i64,
    pub status: InstanceStatus,
    pub completed_at: Option<i64>,
    pub snoozed_until: Option<i64>,
    pub snooze_count: i32,
    pub notes: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
}

/// A span in which a Reminder produces nothing. `until` absent is "until resumed".
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PauseWindow {
    pub from: i64,
    pub until: Option<i64>,
}

/// One Occurrence moved off its scheduled moment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OccurrenceOverride {
    pub from: i64,
    pub to: i64,
    pub notice_id: Option<String>,
}

/// A watched source a notice may be proposed against this Reminder from. Empty `terms`: any notice.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NoticeRule {
    pub source_kind: SourceKind,
    pub source_value: String,
    pub terms: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Notice {
    pub id: String,
    pub user_id: String,
    pub source_kind: SourceKind,
    pub source_value: String,
    pub title: String,
    pub sentence: Option<String>,
    pub received_at: i64,
    pub external_id: Option<String>,
    pub resolution: NoticeResolution,
    pub resolved_at: Option<i64>,
    pub created_at: i64,
    pub updated_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WatchedSource {
    pub id: String,
    pub user_id: String,
    pub kind: SourceKind,
    pub value: String,
    pub label: String,
    pub created_at: i64,
    pub updated_at: i64,
}

/// A JSON number with no fractional part, as a whole millisecond count; `None` for anything
/// else. Convex delivers float64, so `1700000000000.0` and `1.7E12` are as good as the integer.
pub fn whole(value: Option<&Value>) -> Option<i64> {
    let number = value?.as_f64()?;
    // `as` saturates past the i64 range, as Kotlin's `toLong` does.
    if number % 1.0 == 0.0 {
        Some(number as i64)
    } else {
        None
    }
}

pub fn pauses_to_json(windows: &[PauseWindow]) -> Value {
    Value::Array(
        windows
            .iter()
            .map(|w| {
                let mut o = Map::new();
                o.insert("from".into(), json!(w.from));
                if let Some(until) = w.until {
                    o.insert("until".into(), json!(until));
                }
                Value::Object(o)
            })
            .collect(),
    )
}

/// The windows, or none for anything that is not a well-formed array of them: an unread pause
/// is the safe misreading, since the Reminder is then simply not paused.
pub fn pauses_from_json(value: Option<&Value>) -> Vec<PauseWindow> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        let Some(window) = item.as_object() else {
            return Vec::new();
        };
        let Some(from) = whole(window.get("from")) else {
            return Vec::new();
        };
        let until = match window.get("until") {
            None => None,
            Some(u) => match whole(Some(u)) {
                Some(u) => Some(u),
                None => return Vec::new(),
            },
        };
        if until.is_some_and(|u| u <= from) {
            return Vec::new();
        }
        out.push(PauseWindow { from, until });
    }
    out
}

pub fn overrides_to_json(overrides: &[OccurrenceOverride]) -> Value {
    Value::Array(
        overrides
            .iter()
            .map(|o| {
                let mut m = Map::new();
                m.insert("from".into(), json!(o.from));
                m.insert("to".into(), json!(o.to));
                if let Some(id) = &o.notice_id {
                    m.insert("noticeId".into(), json!(id));
                }
                Value::Object(m)
            })
            .collect(),
    )
}

/// The moves, or none for anything that is not a well-formed array of them: every moment then
/// stays where the Schedule put it.
pub fn overrides_from_json(value: Option<&Value>) -> Vec<OccurrenceOverride> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        let Some(entry) = item.as_object() else {
            return Vec::new();
        };
        let (Some(from), Some(to)) = (whole(entry.get("from")), whole(entry.get("to"))) else {
            return Vec::new();
        };
        if to <= from {
            return Vec::new();
        }
        let notice_id = match entry.get("noticeId") {
            None => None,
            Some(Value::String(s)) => Some(s.clone()),
            Some(_) => return Vec::new(),
        };
        out.push(OccurrenceOverride {
            from,
            to,
            notice_id,
        });
    }
    out
}

/// The canonical spelling of a source: an email lowercased and holding an `@`, an app or share
/// exact but trimmed. `None` when it cannot be one.
pub fn normalize_source_value(kind: SourceKind, raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    match kind {
        SourceKind::Email => {
            let lower = trimmed.to_lowercase();
            lower.contains('@').then_some(lower)
        }
        SourceKind::App | SourceKind::Share => Some(trimmed.to_string()),
    }
}

pub fn notice_rules_to_json(rules: &[NoticeRule]) -> Value {
    Value::Array(
        rules
            .iter()
            .map(|r| json!({ "sourceKind": r.source_kind.wire(), "sourceValue": r.source_value, "terms": r.terms }))
            .collect(),
    )
}

/// The rules, or none for anything that is not a well-formed array of them: no rule proposes
/// nothing, which is safe.
pub fn notice_rules_from_json(value: Option<&Value>) -> Vec<NoticeRule> {
    let Some(Value::Array(items)) = value else {
        return Vec::new();
    };
    let mut out = Vec::with_capacity(items.len());
    for item in items {
        let Some(entry) = item.as_object() else {
            return Vec::new();
        };
        let Some(kind) = entry
            .get("sourceKind")
            .and_then(Value::as_str)
            .and_then(SourceKind::from_wire)
        else {
            return Vec::new();
        };
        let Some(raw) = entry.get("sourceValue").and_then(Value::as_str) else {
            return Vec::new();
        };
        let Some(source_value) = normalize_source_value(kind, raw) else {
            return Vec::new();
        };
        let terms = match entry.get("terms") {
            None => Vec::new(),
            Some(Value::Array(terms)) => {
                let mut read = Vec::with_capacity(terms.len());
                for term in terms {
                    let Some(t) = term.as_str() else {
                        return Vec::new();
                    };
                    read.push(t.to_string());
                }
                read
            }
            Some(_) => return Vec::new(),
        };
        out.push(NoticeRule {
            source_kind: kind,
            source_value,
            terms,
        });
    }
    out
}
