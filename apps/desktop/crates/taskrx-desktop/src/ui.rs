//! The window's state: what the service last said, and what the person has chosen to look at.
//! Every rule is the service crate's; this keeps its answers and the person's choices.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use jiff::tz::TimeZone;
use taskrx_desktop_core::domain::{InstanceStatus, ReminderKind};
use taskrx_desktop_core::snooze::{snooze_minutes_or_default, snooze_until};
use taskrx_desktop_core::sync::push::PushCall;
use taskrx_desktop_core::timeline::TimelineRow;
use taskrx_desktop_service::connection::{Event, Table};
use taskrx_desktop_service::data::{Data, Lists};
use taskrx_desktop_service::screens::today::{Row, Today, TodayInput, today};
use taskrx_desktop_service::writes::{Answer, Moment, Refusal, answer_call};

use crate::host::Host;
use crate::theme::Followed;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Phase {
    /// Reading the keyring.
    Starting,
    SignedOut {
        problem: Option<String>,
    },
    /// The browser is open on Clerk.
    SigningIn,
    SignedIn,
}

/// The screen shown once signed in.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Screen {
    Today,
    CatchUp,
    Backlog,
    Proposals,
}

/// Several writes sent as one answer: said once, when the last is back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Batch {
    pub tickets: HashSet<u64>,
    pub failed: usize,
    pub total: usize,
    pub done: String,
}

/// The last bulk answer of Catch up, so one press takes it all back.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BulkUndo {
    pub message: String,
    pub moments: Vec<(String, i64)>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Toast {
    pub text: String,
    pub failed: bool,
    /// On the app clock (`Tx::clock`).
    pub until: f64,
}

pub struct Ui {
    pub phase: Phase,
    pub host: Option<Arc<Host>>,
    pub connected: bool,
    pub lists: Lists,
    pub data: Option<Data>,
    /// A list the server refused, or one that would not decode.
    pub problem: Option<String>,
    /// Clerk could not renew the sign-in for now.
    pub auth_problem: Option<String>,
    pub screen: Screen,
    pub kind: Option<ReminderKind>,
    pub batch: Option<Batch>,
    /// A write to send once the ticket's write has landed, under the same key.
    pub follow_ups: HashMap<u64, (PushCall, String, String)>,
    pub bulk_undo: Option<BulkUndo>,
    pub day_offset: i32,
    pub hide_done: bool,
    pub toast: Option<Toast>,
    /// Writes sent and not yet answered: ticket to the row's key and what to say when done. A
    /// row with one in flight offers nothing, so a second press cannot record a second dose.
    pub sending: HashMap<u64, (String, String)>,
    pub zone: TimeZone,
    /// A fixed clock for headless scenes; the wall clock otherwise.
    pub fixed_now: Option<i64>,
    pub theme: Followed,
    /// The app clock of the last poll, for toasts made outside a handler.
    pub clock: f64,
    /// The half-minute last drawn, so the view re-runs as the day moves and not every frame.
    pub drawn_tick: i64,
}

/// A row's identity on screen: its Reminder and moment.
pub fn row_key(reminder_id: &str, scheduled_time: i64) -> String {
    format!("row-{reminder_id}-{scheduled_time}")
}

pub const TOAST_SECONDS: f64 = 4.0;

impl Ui {
    pub fn new(system_dark: bool) -> Ui {
        Ui {
            phase: Phase::Starting,
            host: None,
            connected: false,
            lists: Lists::default(),
            data: None,
            problem: None,
            auth_problem: None,
            screen: Screen::Today,
            kind: None,
            batch: None,
            follow_ups: HashMap::new(),
            bulk_undo: None,
            day_offset: 0,
            hide_done: true,
            toast: None,
            sending: HashMap::new(),
            zone: TimeZone::system(),
            fixed_now: None,
            theme: Followed::load(system_dark),
            clock: 0.0,
            drawn_tick: 0,
        }
    }

    pub fn in_flight(&self, key: &str) -> bool {
        self.sending.values().any(|(k, _)| k == key)
    }

    pub fn now(&self) -> i64 {
        self.fixed_now
            .unwrap_or_else(|| jiff::Timestamp::now().as_millisecond())
    }

    pub fn today(&self) -> Option<Today> {
        let data = self.data.as_ref()?;
        Some(today(&TodayInput {
            data,
            now: self.now(),
            zone: &self.zone,
            day_offset: self.day_offset,
        }))
    }

    /// The tab shown: the person's pick, else the one Today opens on.
    pub fn tab(&self, today: &Today) -> ReminderKind {
        self.kind.unwrap_or(today.opens_on)
    }

    pub fn say(&mut self, text: impl Into<String>, failed: bool) {
        self.toast = Some(Toast {
            text: text.into(),
            failed,
            until: self.clock + TOAST_SECONDS,
        });
    }

    /// What the connection heard.
    pub fn on_event(&mut self, event: Event) {
        match event {
            Event::Connected(up) => self.connected = up,
            Event::List(table, value) => {
                // An answer can only have come over a live socket.
                self.connected = true;
                match table {
                    Table::Reminders => self.lists.reminders = Some(value),
                    Table::Instances => self.lists.instances = Some(value),
                    Table::Notices => self.lists.notices = Some(value),
                    Table::WatchSources => self.lists.watch_sources = Some(value),
                }
                self.problem = None;
                self.auth_problem = None;
                self.decode();
            }
            Event::ListFailed(_, message) => {
                self.problem = Some(if message.contains("Not authenticated") {
                    "The server hasn't accepted the sign-in yet".to_string()
                } else {
                    message
                })
            }
            Event::SignedOut => self.signed_out(None),
            Event::AuthUnreachable(why) => self.auth_problem = Some(why),
            Event::Wrote { ticket, result } => {
                let (_, what) = self.sending.remove(&ticket).unwrap_or_default();
                if let Some((then, key, done)) = self.follow_ups.remove(&ticket) {
                    match result {
                        Ok(()) => {
                            let _ = self.send_call(then, key, done);
                        }
                        Err(e) => self.say(format!("Couldn't save that: {e}"), true),
                    }
                    return;
                }
                if let Some(batch) = self.batch.as_mut()
                    && batch.tickets.remove(&ticket)
                {
                    if result.is_err() {
                        batch.failed += 1;
                    }
                    if batch.tickets.is_empty() {
                        let batch = self.batch.take().expect("the batch");
                        if batch.failed == 0 {
                            self.say(batch.done, false);
                        } else {
                            self.say(
                                format!("Couldn't answer {} of {}", batch.failed, batch.total),
                                true,
                            );
                        }
                    }
                    return;
                }
                match result {
                    Ok(()) => self.say(what, false),
                    Err(e) => self.say(format!("Couldn't save that: {e}"), true),
                }
            }
        }
    }

    fn decode(&mut self) {
        if !self.lists.has_today() {
            return;
        }
        match Data::decode(&self.lists) {
            Ok(data) => self.data = Some(data),
            Err(e) => self.problem = Some(format!("Couldn't read the data: {e}")),
        }
    }

    /// Back to the sign-in screen, holding nothing of the person's.
    pub fn signed_out(&mut self, problem: Option<String>) {
        if let Some(host) = &self.host {
            host.disconnect();
        }
        self.phase = Phase::SignedOut { problem };
        self.lists = Lists::default();
        self.data = None;
        self.connected = false;
        self.sending.clear();
        self.batch = None;
        self.follow_ups.clear();
        self.bulk_undo = None;
        self.screen = Screen::Today;
    }

    /// Answers one row. `done` is what to say once the server has it.
    pub fn answer(&mut self, row: &Row, answer: Answer, done: String) {
        let moment = Moment {
            reminder_id: &row.row.reminder_id,
            scheduled_time: row.row.scheduled_time,
            instance_id: row.row.instance_id.as_deref(),
        };
        let key = row_key(moment.reminder_id, moment.scheduled_time);
        let _ = self.send(&moment, answer, key, done);
    }

    /// Records an as-needed dose as taken now.
    pub fn take_as_needed(&mut self, reminder_id: &str, name: &str) {
        let now = self.now();
        let moment = Moment {
            reminder_id,
            scheduled_time: now,
            instance_id: None,
        };
        let key = format!("as-needed-{reminder_id}");
        let _ = self.send(
            &moment,
            Answer::Take { at: now },
            key,
            format!("{name} recorded as taken"),
        );
    }

    /// Completes a Backlog item: one Instance at the moment of the act (ADR 0002).
    pub fn complete_backlog(&mut self, reminder_id: &str, name: &str) {
        let now = self.now();
        let moment = Moment {
            reminder_id,
            scheduled_time: now,
            instance_id: None,
        };
        let key = format!("backlog-{reminder_id}");
        let _ = self.send(
            &moment,
            Answer::Take { at: now },
            key,
            format!("{name} recorded"),
        );
    }

    /// Answers Catch up rows, Take or Skip. A Take is stamped with the moment it was due: days
    /// later that is the best guess there is, as on the phone.
    pub fn answer_late(&mut self, rows: &[TimelineRow], take: bool) {
        let mut tickets = HashSet::new();
        let mut moments = Vec::new();
        for r in rows {
            let moment = Moment {
                reminder_id: &r.reminder_id,
                scheduled_time: r.scheduled_time,
                instance_id: r.instance_id.as_deref(),
            };
            let answer = if take {
                Answer::Take {
                    at: r.scheduled_time,
                }
            } else {
                Answer::Skip
            };
            let key = row_key(&r.reminder_id, r.scheduled_time);
            if let Some(ticket) = self.send(&moment, answer, key, String::new()) {
                tickets.insert(ticket);
                moments.push((r.reminder_id.clone(), r.scheduled_time));
            }
        }
        if tickets.is_empty() {
            return;
        }
        let word = match (
            take,
            rows.iter().all(|r| r.kind == ReminderKind::Medication),
        ) {
            (false, _) => "skipped",
            (true, true) => "taken",
            (true, false) => "done",
        };
        let message = match rows {
            [one] => format!("{} {word}", one.name),
            _ => format!("{} {word}", tickets.len()),
        };
        self.batch = Some(Batch {
            total: tickets.len(),
            tickets,
            failed: 0,
            done: message.clone(),
        });
        self.bulk_undo = Some(BulkUndo { message, moments });
    }

    /// Takes the last Catch up answer back, every row of it that the server now shows answered.
    pub fn undo_late(&mut self) {
        let Some(last) = self.bulk_undo.take() else {
            return;
        };
        let Some(data) = self.data.as_ref() else {
            return;
        };
        let found: Vec<(String, i64, String)> = last
            .moments
            .iter()
            .filter_map(|(reminder, time)| {
                data.instances
                    .iter()
                    .find(|i| {
                        &i.reminder_id == reminder
                            && i.scheduled_time == *time
                            && i.status != InstanceStatus::Pending
                    })
                    .map(|i| (reminder.clone(), *time, i.id.clone()))
            })
            .collect();
        let mut tickets = HashSet::new();
        for (reminder_id, time, instance_id) in &found {
            let moment = Moment {
                reminder_id,
                scheduled_time: *time,
                instance_id: Some(instance_id),
            };
            if let Some(t) = self.send(
                &moment,
                Answer::Reopen,
                row_key(reminder_id, *time),
                String::new(),
            ) {
                tickets.insert(t);
            }
        }
        if tickets.is_empty() {
            self.say("Nothing to take back yet", true);
            return;
        }
        self.batch = Some(Batch {
            total: tickets.len(),
            tickets,
            failed: 0,
            done: "Taken back".to_string(),
        });
    }

    /// Sends one answer; the ticket, or `None` when it was refused or there is no connection.
    fn send(&mut self, moment: &Moment, answer: Answer, key: String, done: String) -> Option<u64> {
        if self.in_flight(&key) {
            return None;
        }
        let (Some(host), Some(data)) = (self.host.clone(), self.data.as_ref()) else {
            self.say("Not connected", true);
            return None;
        };
        let Some(user) = host.user_id() else {
            self.say("Not signed in", true);
            return None;
        };
        match answer_call(data, &user, moment, answer, self.now()) {
            Ok(call) => self.send_call(call, key, done),
            Err(refusal) => {
                self.say(refused(&refusal), true);
                None
            }
        }
    }

    /// Sends a built call under `key`; `done` is said when the server has it.
    pub fn send_call(&mut self, call: PushCall, key: String, done: String) -> Option<u64> {
        let Some(host) = self.host.clone() else {
            self.say("Not connected", true);
            return None;
        };
        match host.write(call) {
            Some(ticket) => {
                self.sending.insert(ticket, (key, done));
                Some(ticket)
            }
            None => {
                self.say("Not connected", true);
                None
            }
        }
    }

    /// Two writes where the second only makes sense once the first has landed: a notice is
    /// settled after its move or its task is saved, never before.
    pub fn send_then(&mut self, first: PushCall, then: PushCall, key: String, done: String) {
        if self.in_flight(&key) {
            return;
        }
        if let Some(ticket) = self.send_call(first, key.clone(), String::new()) {
            self.follow_ups.insert(ticket, (then, key, done));
        }
    }

    pub fn refuse(&mut self, refusal: &Refusal) {
        self.say(refused(refusal), true);
    }

    pub fn user_id(&self) -> Option<String> {
        self.host.as_ref().and_then(|h| h.user_id())
    }

    pub fn snooze_until(&self) -> i64 {
        snooze_until(self.now(), snooze_minutes_or_default(None))
    }
}

fn refused(refusal: &Refusal) -> String {
    match refusal {
        Refusal::NotAllowed => "That can't be done from here".into(),
        Refusal::NothingToReopen => "Nothing to take back".into(),
        Refusal::NotOnServer => "That isn't on the server yet".into(),
        Refusal::Rejected(why) => format!("Couldn't send that: {why}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ui() -> Ui {
        let mut ui = Ui::new(true);
        ui.zone = TimeZone::get("America/New_York").unwrap();
        ui
    }

    #[test]
    fn the_day_is_drawn_once_reminders_and_instances_have_both_arrived() {
        let mut ui = ui();
        ui.on_event(Event::List(Table::Reminders, json!([])));
        assert!(ui.data.is_none());
        ui.on_event(Event::List(Table::Instances, json!([])));
        assert!(ui.data.is_some());
    }

    #[test]
    fn a_list_that_will_not_decode_is_said_and_the_last_day_kept() {
        let mut ui = ui();
        ui.on_event(Event::List(Table::Reminders, json!([])));
        ui.on_event(Event::List(Table::Instances, json!([])));
        ui.on_event(Event::List(Table::Reminders, json!([{ "_id": 1 }])));
        assert!(ui.problem.as_deref().unwrap().starts_with("Couldn't read"));
        assert!(ui.data.is_some());
    }

    #[test]
    fn signing_out_forgets_the_data() {
        let mut ui = ui();
        ui.phase = Phase::SignedIn;
        ui.on_event(Event::List(Table::Reminders, json!([])));
        ui.on_event(Event::List(Table::Instances, json!([])));
        ui.on_event(Event::SignedOut);
        assert_eq!(ui.phase, Phase::SignedOut { problem: None });
        assert!(ui.data.is_none());
        assert_eq!(ui.lists, Lists::default());
    }

    #[test]
    fn a_write_answered_is_said() {
        let mut ui = ui();
        ui.sending
            .insert(3, ("row-r1-1".into(), "Flonase taken".into()));
        assert!(ui.in_flight("row-r1-1"));
        ui.on_event(Event::Wrote {
            ticket: 3,
            result: Ok(()),
        });
        assert_eq!(ui.toast.as_ref().unwrap().text, "Flonase taken");
        ui.sending.insert(4, ("row-r1-2".into(), "x".into()));
        ui.on_event(Event::Wrote {
            ticket: 4,
            result: Err("Timed out waiting for the server".into()),
        });
        assert!(ui.toast.as_ref().unwrap().failed);
        assert!(ui.sending.is_empty());
        assert!(!ui.in_flight("row-r1-1"));
    }

    #[test]
    fn a_batch_is_said_once_when_its_last_write_is_back() {
        let mut ui = ui();
        ui.batch = Some(Batch {
            tickets: [1, 2].into_iter().collect(),
            failed: 0,
            total: 2,
            done: "2 taken".into(),
        });
        ui.on_event(Event::Wrote {
            ticket: 1,
            result: Ok(()),
        });
        assert!(ui.toast.is_none());
        ui.on_event(Event::Wrote {
            ticket: 2,
            result: Err("x".into()),
        });
        let toast = ui.toast.as_ref().unwrap();
        assert_eq!(toast.text, "Couldn't answer 1 of 2");
        assert!(toast.failed);
        assert!(ui.batch.is_none());
    }

    #[test]
    fn a_second_write_is_not_sent_when_the_first_fails() {
        let mut ui = ui();
        let then = PushCall {
            function: "notices:update",
            args: Vec::new(),
        };
        ui.sending.insert(7, ("notice-n1".into(), String::new()));
        ui.follow_ups
            .insert(7, (then, "notice-n1".into(), "Moved".into()));
        ui.on_event(Event::Wrote {
            ticket: 7,
            result: Err("Reminder not found".into()),
        });
        assert!(ui.follow_ups.is_empty());
        assert!(!ui.in_flight("notice-n1"));
        assert!(ui.toast.as_ref().unwrap().failed);
    }

    #[test]
    fn a_list_arriving_means_connected() {
        let mut ui = ui();
        ui.on_event(Event::List(Table::Notices, json!([])));
        assert!(ui.connected);
    }

    #[test]
    fn without_a_connection_nothing_is_sent() {
        let mut ui = ui();
        ui.take_as_needed("r1", "Advil");
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }
}
