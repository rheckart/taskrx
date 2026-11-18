//! `today`: the timeline, the Tasks tab, batches, the sweep, snoozes and undo.

use serde_json::{Value, json};
use taskrx_desktop_core::batch::{BatchAction, batch_notification_id, batch_slots, batch_text};
use taskrx_desktop_core::domain::{InstanceStatus, Reminder, ReminderKind};
use taskrx_desktop_core::sweep::{
    MissedVerdict, PlanMissedSweepInput, plan_missed_sweep, snooze_ran_out,
};
use taskrx_desktop_core::tasks::{TaskSections, TasksTabInput, assemble_tasks_tab, section_tasks};
use taskrx_desktop_core::timeline::{
    ProjectTimelineInput, TimelineRow, TimelineWindow, project_timeline,
};
use taskrx_desktop_core::undo::{
    is_scheduled_occurrence, undo_verdict, undo_will_be_missed, undoable_instance_ids,
};

use super::domain::instances;
use super::schedule::reminder;
use super::{Outcome, field, int, text, unknown_call, zone};

pub fn row_json(r: &TimelineRow) -> Value {
    json!({
        "reminderId": r.reminder_id, "instanceId": r.instance_id, "name": r.name, "dosage": r.dosage,
        "kind": r.kind.wire(), "scheduledTime": r.scheduled_time, "status": r.status.wire(),
        "snoozedUntil": r.snoozed_until, "isNext": r.is_next, "completedAt": r.completed_at,
    })
}

fn opt_text(v: &Value, key: &str) -> Option<String> {
    v.get(key).and_then(Value::as_str).map(str::to_string)
}

fn opt_int(v: &Value, key: &str) -> Option<i64> {
    v.get(key).and_then(Value::as_f64).map(|n| n as i64)
}

pub fn row_from_json(v: &Value) -> Result<TimelineRow, String> {
    Ok(TimelineRow {
        reminder_id: opt_text(v, "reminderId").ok_or("a row with no reminderId")?,
        instance_id: opt_text(v, "instanceId"),
        name: opt_text(v, "name").ok_or("a row with no name")?,
        dosage: opt_text(v, "dosage"),
        kind: ReminderKind::from_wire(&opt_text(v, "kind").unwrap_or_default())
            .ok_or("a row of an unknown kind")?,
        scheduled_time: opt_int(v, "scheduledTime").ok_or("a row with no scheduledTime")?,
        status: InstanceStatus::from_wire(&opt_text(v, "status").unwrap_or_default())
            .ok_or("a row of an unknown status")?,
        snoozed_until: opt_int(v, "snoozedUntil"),
        is_next: v
            .get("isNext")
            .and_then(Value::as_bool)
            .ok_or("a row with no isNext")?,
        completed_at: opt_int(v, "completedAt"),
    })
}

fn rows(input: &Value, key: &str) -> Result<Vec<TimelineRow>, String> {
    field(input, key)?
        .as_array()
        .ok_or("rows are not a list")?
        .iter()
        .map(row_from_json)
        .collect()
}

fn reminders(input: &Value) -> Result<Vec<Reminder>, String> {
    field(input, "reminders")?
        .as_array()
        .ok_or("reminders are not a list")?
        .iter()
        .map(reminder)
        .collect()
}

fn sections_json(s: &TaskSections) -> Value {
    json!({
        "overdue": s.overdue.iter().map(row_json).collect::<Vec<_>>(),
        "dueToday": s.due_today.iter().map(row_json).collect::<Vec<_>>(),
        "upcoming": s.upcoming.iter().map(|d| json!({ "dayStart": d.day_start, "rows": d.rows.iter().map(row_json).collect::<Vec<_>>() })).collect::<Vec<_>>(),
    })
}

fn verdict_json(v: &MissedVerdict) -> Value {
    json!({ "reminderId": v.reminder_id, "reminderKind": v.reminder_kind.wire(), "scheduledTime": v.scheduled_time, "instanceId": v.instance_id })
}

pub fn case(input: &Value) -> Outcome {
    match text(input, "call")? {
        "projectTimeline" => {
            let tz = zone(input)?;
            let (r, i) = (reminders(input)?, instances(field(input, "instances")?)?);
            let out = project_timeline(&ProjectTimelineInput {
                reminders: &r,
                instances: &i,
                window: TimelineWindow {
                    start: int(input, "windowStart")?,
                    end: int(input, "windowEnd")?,
                },
                now: int(input, "now")?,
                zone: &tz,
                max_occurrences_per_reminder: int(input, "maxOccurrencesPerReminder")?,
            });
            Ok(json!(out.iter().map(row_json).collect::<Vec<_>>()))
        }
        "sectionTasks" => Ok(sections_json(&section_tasks(
            &rows(input, "rows")?,
            int(input, "now")?,
            &zone(input)?,
        ))),
        "assembleTasksTab" => {
            let tz = zone(input)?;
            let r = reminders(input)?;
            let (inside, older) = (
                instances(field(input, "instances")?)?,
                instances(field(input, "olderInstances")?)?,
            );
            Ok(sections_json(&assemble_tasks_tab(&TasksTabInput {
                reminders: &r,
                instances: &inside,
                older_instances: &older,
                now: int(input, "now")?,
                zone: &tz,
            })))
        }
        "batchSlots" => {
            let mut slots = batch_slots(&rows(input, "rows")?);
            slots.sort_by_key(|(t, _)| *t);
            Ok(json!(slots.iter().map(|(t, slot)| json!({ "scheduledTime": t, "rows": slot.iter().map(row_json).collect::<Vec<_>>() })).collect::<Vec<_>>()))
        }
        "batchText" => {
            let names: Vec<String> = field(input, "names")?
                .as_array()
                .ok_or("names are not a list")?
                .iter()
                .filter_map(|n| n.as_str().map(str::to_string))
                .collect();
            let t = batch_text(&names);
            Ok(json!({ "title": t.title, "body": t.body }))
        }
        "batchNotificationId" => Ok(json!(batch_notification_id(int(input, "scheduledTime")?))),
        "BatchAction" => {
            let wires: Vec<Option<&str>> = BatchAction::ALL
                .iter()
                .map(|a| Some(a.wire()))
                .chain([Some("snooze"), Some("later"), None])
                .collect();
            Ok(json!(wires.iter().map(|w| json!({ "wire": w, "fromWire": BatchAction::from_wire(*w).map(|a| a.wire()) })).collect::<Vec<_>>()))
        }
        "planMissedSweep" => {
            let tz = zone(input)?;
            let (r, i) = (reminders(input)?, instances(field(input, "instances")?)?);
            let out = plan_missed_sweep(&PlanMissedSweepInput {
                reminders: &r,
                instances: &i,
                now: int(input, "now")?,
                grace_period_ms: int(input, "gracePeriodMs")?,
                lookback_ms: int(input, "lookbackMs")?,
                zone: &tz,
            });
            Ok(json!(out.iter().map(verdict_json).collect::<Vec<_>>()))
        }
        "snoozeRanOut" => {
            let one = instances(&Value::Array(vec![field(input, "instance")?.clone()]))?;
            Ok(json!(snooze_ran_out(
                &one[0],
                int(input, "now")?,
                int(input, "gracePeriodMs")?
            )))
        }
        "undoVerdict" => {
            let status =
                InstanceStatus::from_wire(text(input, "status")?).ok_or("unknown status")?;
            let other = field(input, "otherOpenAtMoment")?
                .as_bool()
                .ok_or("otherOpenAtMoment is not a boolean")?;
            Ok(json!(
                undo_verdict(
                    status,
                    &reminder(field(input, "reminder")?)?,
                    int(input, "scheduledTime")?,
                    other,
                    &zone(input)?
                )
                .wire()
            ))
        }
        "isScheduledOccurrence" => Ok(json!(is_scheduled_occurrence(
            &reminder(field(input, "reminder")?)?,
            int(input, "scheduledTime")?,
            &zone(input)?
        ))),
        "undoWillBeMissed" => Ok(json!(undo_will_be_missed(
            int(input, "scheduledTime")?,
            int(input, "now")?,
            int(input, "gracePeriodMs")?
        ))),
        "undoableInstanceIds" => Ok(json!(undoable_instance_ids(
            &rows(input, "rows")?,
            &reminders(input)?,
            &zone(input)?
        ))),
        _ => unknown_call(input),
    }
}
