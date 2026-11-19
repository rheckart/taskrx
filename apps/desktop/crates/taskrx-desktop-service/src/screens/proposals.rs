//! Proposals: the notices still waiting for a decision, the date each names, and the move each
//! matching task is offered. Mirrors the phone's `NoticesViewModel`; the reader and the rules
//! are the ported ones. Nothing is applied without the person pressing for it.

use jiff::ToSpan;
use jiff::tz::TimeZone;
use taskrx_desktop_core::domain::{
    MoveRefusal, Notice, NoticeResolution, OccurrenceOverride, ReminderKind, move_refusal,
    with_override,
};
use taskrx_desktop_core::notices::{
    DEFAULT_NOTICE_HOUR, Proposal, ReadDate, notice_text, proposals_for, read_notice_date,
};
use taskrx_desktop_core::time::{instant_at, local_date};

use crate::data::Data;
use crate::writes::{Refusal, ReminderPatch};

/// How far either side of now the Instances are read that a move's refusal looks at.
const LOOKAROUND_MS: i64 = 400 * 86_400_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Offer {
    pub proposal: Proposal,
    pub task_name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NoticeView {
    pub notice: Notice,
    pub reading: ReadDate,
    pub offers: Vec<Offer>,
    /// Where "New task" puts the task: the date named, else tomorrow, at the time named or 09:00,
    /// never in the past.
    pub new_task_at: i64,
}

pub fn proposals(data: &Data, now: i64, zone: &TimeZone) -> Vec<NoticeView> {
    let instances: Vec<_> = data
        .instances
        .iter()
        .filter(|i| (i.scheduled_time - now).abs() <= LOOKAROUND_MS)
        .cloned()
        .collect();
    let tasks: Vec<_> = data
        .reminders
        .iter()
        .filter(|r| r.kind == ReminderKind::Task)
        .cloned()
        .collect();
    let mut open: Vec<&Notice> = data
        .notices
        .iter()
        .filter(|n| n.resolution == NoticeResolution::None)
        .collect();
    open.sort_by_key(|n| std::cmp::Reverse(n.received_at));
    open.into_iter()
        .map(|n| {
            let reading = read_notice_date(&notice_text(n), now, zone);
            let offers = proposals_for(n, &tasks, &instances, now, zone)
                .into_iter()
                .map(|p| Offer {
                    task_name: data
                        .reminder(&p.reminder_id)
                        .map_or_else(String::new, |r| r.name.clone()),
                    proposal: p,
                })
                .collect();
            NoticeView {
                new_task_at: new_task_at(&reading, now, zone),
                notice: n.clone(),
                reading,
                offers,
            }
        })
        .collect()
}

fn new_task_at(reading: &ReadDate, now: i64, zone: &TimeZone) -> i64 {
    let (date, hour, minute) = match reading {
        ReadDate::Found { date, time, .. } => (
            *date,
            time.map_or(DEFAULT_NOTICE_HOUR, |t| t.hour()),
            time.map_or(0, |t| t.minute()),
        ),
        ReadDate::Unread(_) => (
            local_date(now, zone)
                .checked_add(1.days())
                .expect("tomorrow is in range"),
            DEFAULT_NOTICE_HOUR,
            0,
        ),
    };
    let at = instant_at(date, hour, minute, 0, 0, zone);
    if at > now {
        at
    } else {
        instant_at(
            date.checked_add(1.days()).expect("a day in range"),
            hour,
            minute,
            0,
            0,
            zone,
        )
    }
}

/// The edit that applies an offer: the moved moment as an override carrying the notice, after
/// the refusal is checked again with the clock as it is now.
pub fn apply_patch(
    data: &Data,
    notice_id: &str,
    proposal: &Proposal,
    now: i64,
) -> Result<ReminderPatch, Refusal> {
    let from = proposal.from.ok_or(Refusal::NotAllowed)?;
    let reminder = data
        .reminder(&proposal.reminder_id)
        .ok_or(Refusal::NotOnServer)?;
    let at_moment: Vec<_> = data
        .instances
        .iter()
        .filter(|i| i.reminder_id == reminder.id && i.scheduled_time == from)
        .cloned()
        .collect();
    let refusal = if proposal.to <= now {
        Some(MoveRefusal::InThePast)
    } else {
        move_refusal(reminder, from, proposal.to, now, &at_moment)
    };
    if refusal.is_some() {
        return Err(Refusal::NotAllowed);
    }
    Ok(ReminderPatch {
        overrides: Some(with_override(
            &reminder.overrides,
            OccurrenceOverride {
                from,
                to: proposal.to,
                notice_id: Some(notice_id.to_string()),
            },
        )),
        ..ReminderPatch::default()
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::data::Lists;
    use serde_json::json;

    fn at(local: &str) -> i64 {
        format!("{local}[America/New_York]")
            .parse::<jiff::Zoned>()
            .unwrap()
            .timestamp()
            .as_millisecond()
    }

    fn data() -> Data {
        Data::decode(&Lists {
            reminders: Some(json!([{
                "_id": "srv_r1", "localId": "r1", "userId": "u", "kind": "task",
                "name": "Pick up prescription",
                "schedule": { "type": "weekly", "daysOfWeek": [5], "times": ["17:00"] },
                "priority": "normal", "active": true, "createdAt": 1.0, "updatedAt": 1.0,
                "noticeRules": [{ "sourceKind": "email", "sourceValue": "pharmacy@example.com", "terms": [] }]
            }])),
            instances: Some(json!([])),
            notices: Some(json!([
                {
                    "_id": "srv_n1", "localId": "n1", "userId": "u", "sourceKind": "email",
                    "sourceValue": "pharmacy@example.com", "title": "Your order is ready",
                    "sentence": "Your prescription will be ready on November 25 at 3pm.",
                    "receivedAt": at("2026-11-18T08:00") as f64, "resolution": "none",
                    "createdAt": 1.0, "updatedAt": 1.0
                },
                {
                    "_id": "srv_n2", "localId": "n2", "userId": "u", "sourceKind": "email",
                    "sourceValue": "other@example.com", "title": "Newsletter",
                    "receivedAt": at("2026-11-18T09:00") as f64, "resolution": "none",
                    "createdAt": 1.0, "updatedAt": 1.0
                },
                {
                    "_id": "srv_n3", "localId": "n3", "userId": "u", "sourceKind": "email",
                    "sourceValue": "pharmacy@example.com", "title": "Old",
                    "receivedAt": 1.0, "resolution": "dismissed", "createdAt": 1.0, "updatedAt": 1.0
                }
            ])),
            ..Lists::default()
        })
        .unwrap()
    }

    #[test]
    fn open_notices_newest_first_with_what_their_rules_propose() {
        let zone = TimeZone::get("America/New_York").unwrap();
        let now = at("2026-11-18T10:00");
        let views = proposals(&data(), now, &zone);
        assert_eq!(
            views
                .iter()
                .map(|v| v.notice.id.as_str())
                .collect::<Vec<_>>(),
            vec!["n2", "n1"]
        );
        // No date and no rule: tomorrow at nine, nothing proposed.
        assert!(views[0].offers.is_empty());
        assert_eq!(views[0].new_task_at, at("2026-11-19T09:00"));
        // The pharmacy's notice moves the task's nearest Friday to the date and time it names.
        let offer = &views[1].offers[0];
        assert_eq!(offer.task_name, "Pick up prescription");
        assert_eq!(offer.proposal.to, at("2026-11-25T15:00"));
        assert_eq!(views[1].new_task_at, at("2026-11-25T15:00"));
        assert!(offer.proposal.applies(), "{:?}", offer.proposal);
        let patch = apply_patch(&data(), "n1", &offer.proposal, now).unwrap();
        let overrides = patch.overrides.unwrap();
        assert_eq!(overrides.len(), 1);
        assert_eq!(overrides[0].to, at("2026-11-25T15:00"));
        assert_eq!(overrides[0].notice_id.as_deref(), Some("n1"));
        // Once the moment has passed, the move is refused when pressed.
        assert_eq!(
            apply_patch(&data(), "n1", &offer.proposal, at("2026-11-26T00:00")),
            Err(Refusal::NotAllowed)
        );
    }
}
