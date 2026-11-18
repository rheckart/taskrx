//! The window's state: what the service last said, and what the person has chosen to look at.
//! Every rule is the service crate's; this keeps its answers and the person's choices.

use std::collections::HashMap;
use std::sync::Arc;

use jiff::tz::TimeZone;
use taskrx_desktop_core::domain::ReminderKind;
use taskrx_desktop_core::snooze::{snooze_minutes_or_default, snooze_until};
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
    pub kind: Option<ReminderKind>,
    pub day_offset: i32,
    pub hide_done: bool,
    pub toast: Option<Toast>,
    /// Writes sent and not yet answered: ticket to what the person did.
    pub sending: HashMap<u64, String>,
    pub zone: TimeZone,
    /// A fixed clock for headless scenes; the wall clock otherwise.
    pub fixed_now: Option<i64>,
    pub theme: Followed,
    /// The app clock of the last poll, for toasts made outside a handler.
    pub clock: f64,
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
            kind: None,
            day_offset: 0,
            hide_done: true,
            toast: None,
            sending: HashMap::new(),
            zone: TimeZone::system(),
            fixed_now: None,
            theme: Followed::load(system_dark),
            clock: 0.0,
        }
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
            Event::ListFailed(_, message) => self.problem = Some(message),
            Event::SignedOut => self.signed_out(None),
            Event::AuthUnreachable(why) => self.auth_problem = Some(why),
            Event::Wrote { ticket, result } => {
                let what = self.sending.remove(&ticket).unwrap_or_default();
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
    }

    /// Answers one row. `done` is what to say once the server has it.
    pub fn answer(&mut self, row: &Row, answer: Answer, done: String) {
        let moment = Moment {
            reminder_id: &row.row.reminder_id,
            scheduled_time: row.row.scheduled_time,
            instance_id: row.row.instance_id.as_deref(),
        };
        self.send(&moment, answer, done);
    }

    /// Records an as-needed dose as taken now.
    pub fn take_as_needed(&mut self, reminder_id: &str, name: &str) {
        let now = self.now();
        let moment = Moment {
            reminder_id,
            scheduled_time: now,
            instance_id: None,
        };
        self.send(
            &moment,
            Answer::Take { at: now },
            format!("{name} recorded as taken"),
        );
    }

    fn send(&mut self, moment: &Moment, answer: Answer, done: String) {
        let (Some(host), Some(data)) = (self.host.clone(), self.data.as_ref()) else {
            self.say("Not connected", true);
            return;
        };
        let Some(user) = host.user_id() else {
            self.say("Not signed in", true);
            return;
        };
        match answer_call(data, &user, moment, answer, self.now()) {
            Ok(call) => match host.write(call) {
                Some(ticket) => {
                    self.sending.insert(ticket, done);
                }
                None => self.say("Not connected", true),
            },
            Err(refusal) => self.say(refused(&refusal), true),
        }
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
        ui.sending.insert(3, "Flonase taken".into());
        ui.on_event(Event::Wrote {
            ticket: 3,
            result: Ok(()),
        });
        assert_eq!(ui.toast.as_ref().unwrap().text, "Flonase taken");
        ui.sending.insert(4, "x".into());
        ui.on_event(Event::Wrote {
            ticket: 4,
            result: Err("Timed out waiting for the server".into()),
        });
        assert!(ui.toast.as_ref().unwrap().failed);
        assert!(ui.sending.is_empty());
    }

    #[test]
    fn without_a_connection_nothing_is_sent() {
        let mut ui = ui();
        ui.take_as_needed("r1", "Advil");
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }
}
