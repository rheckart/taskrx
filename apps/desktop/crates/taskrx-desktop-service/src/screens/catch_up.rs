//! Catch up: the week's missed doses and tasks, to answer late. Mirrors the phone's
//! `CatchUpViewModel` and `CatchUpReader`.
//!
//! The phone runs its sweep first, so the rows it reads are already Missed. The desktop records
//! no verdict (ADR 0016): it projects the same window and reads as Missed the moments the sweep
//! would mark, which gives the rows the phone shows after its sweep.

use jiff::tz::TimeZone;
use taskrx_desktop_core::catchup::{CATCH_UP_WINDOW_MS, catch_up_days, needs_catch_up};
use taskrx_desktop_core::domain::{InstanceStatus, is_answerable};
use taskrx_desktop_core::timeline::{
    DEFAULT_MAX_OCCURRENCES_PER_REMINDER, ProjectTimelineInput, TimelineRow, TimelineWindow,
    project_timeline, timeline_row_key,
};

use super::today::shown_missed;
use crate::data::Data;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CatchUpDay {
    pub day_start: i64,
    pub rows: Vec<TimelineRow>,
}

/// The days with something to answer, newest first. A row's `status` is Missed whether the
/// server holds it so or the sweep would; `instance_id` is what the server holds, if anything.
pub fn catch_up(data: &Data, now: i64, zone: &TimeZone) -> Vec<CatchUpDay> {
    let window = TimelineWindow {
        start: now - CATCH_UP_WINDOW_MS,
        end: now,
    };
    let stored: Vec<_> = data
        .instances
        .iter()
        .filter(|i| i.scheduled_time >= window.start && i.scheduled_time <= window.end)
        .cloned()
        .collect();
    let missed = shown_missed(data, now, zone);
    let rows: Vec<TimelineRow> = project_timeline(&ProjectTimelineInput {
        reminders: &data.reminders,
        instances: &stored,
        window,
        now,
        zone,
        max_occurrences_per_reminder: DEFAULT_MAX_OCCURRENCES_PER_REMINDER,
    })
    .into_iter()
    .map(|mut r| {
        // Only an unanswered row reads as Missed: a moment can hold an answered row beside a
        // stale pending one, and the sweep's verdict on the stale one is not this row's.
        if is_answerable(r.status)
            && missed.contains(&timeline_row_key(&r.reminder_id, r.scheduled_time))
        {
            r.status = InstanceStatus::Missed;
        }
        r
    })
    .collect();
    catch_up_days(&needs_catch_up(&rows), zone)
        .into_iter()
        .map(|d| CatchUpDay {
            day_start: d.day_start,
            rows: d.rows,
        })
        .collect()
}

pub fn total(days: &[CatchUpDay]) -> usize {
    days.iter().map(|d| d.rows.len()).sum()
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

    #[test]
    fn the_moments_past_grace_and_unanswered_are_listed_by_day_newest_first() {
        let data = Data::decode(&Lists {
            reminders: Some(json!([{
                "_id": "srv_r1", "localId": "r1", "userId": "u", "kind": "medication",
                "name": "Zinc", "schedule": { "type": "daily", "times": ["09:00"] },
                "priority": "normal", "active": true,
                "createdAt": at("2026-11-16T00:00") as f64, "updatedAt": 1.0
            }])),
            instances: Some(json!([{
                "_id": "srv_i1", "localId": "i1", "userId": "u", "reminderId": "srv_r1",
                "reminderKind": "medication", "scheduledTime": at("2026-11-17T09:00") as f64,
                "status": "completed", "completedAt": at("2026-11-17T09:00") as f64,
                "snoozeCount": 0.0, "createdAt": 1.0, "updatedAt": 2.0
            }])),
            ..Lists::default()
        })
        .unwrap();
        let zone = TimeZone::get("America/New_York").unwrap();
        // 10:30 on the 18th: today's 09:00 is inside its grace, the 17th's is answered, and
        // the 16th's was never answered.
        let days = catch_up(&data, at("2026-11-18T10:30"), &zone);
        assert_eq!(days.len(), 1);
        assert_eq!(days[0].day_start, at("2026-11-16T00:00"));
        assert_eq!(days[0].rows[0].status, InstanceStatus::Missed);
        assert_eq!(days[0].rows[0].instance_id, None);
        assert_eq!(total(&days), 1);
        // Past today's grace, it joins, on a newer day first.
        let later = catch_up(&data, at("2026-11-18T11:30"), &zone);
        assert_eq!(later[0].day_start, at("2026-11-18T00:00"));
        assert_eq!(total(&later), 2);
    }

    #[test]
    fn an_answered_moment_is_not_listed_beside_a_stale_pending_row() {
        let moment = at("2026-11-16T09:00");
        let instance = |id: &str, status: &str, updated: f64| {
            json!({
                "_id": format!("srv_{id}"), "localId": id, "userId": "u", "reminderId": "srv_r1",
                "reminderKind": "medication", "scheduledTime": moment as f64, "status": status,
                "completedAt": if status == "completed" { Some(moment as f64) } else { None },
                "snoozeCount": 0.0, "createdAt": 1.0, "updatedAt": updated
            })
        };
        let data = Data::decode(&Lists {
            reminders: Some(json!([{
                "_id": "srv_r1", "localId": "r1", "userId": "u", "kind": "medication",
                "name": "Zinc", "schedule": { "type": "daily", "times": ["09:00"] },
                "priority": "normal", "active": true,
                "createdAt": at("2026-11-16T00:00") as f64, "updatedAt": 1.0
            }])),
            // Two rows at one moment, as issue #21's duplicates left: the answer and a pending one.
            instances: Some(json!([
                instance("old", "pending", 1.0),
                instance("taken", "completed", 2.0),
            ])),
            ..Lists::default()
        })
        .unwrap();
        let zone = TimeZone::get("America/New_York").unwrap();
        let days = catch_up(&data, at("2026-11-16T12:00"), &zone);
        assert_eq!(total(&days), 0, "{days:?}");
    }
}
