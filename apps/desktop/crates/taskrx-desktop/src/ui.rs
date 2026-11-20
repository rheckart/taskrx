//! The window's state: what the service last said, and what the person has chosen to look at.
//! Every rule is the service crate's; this keeps its answers and the person's choices.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;

use jiff::tz::TimeZone;
use rune::TextEdit;
use taskrx_desktop_core::domain::{InstanceStatus, ReminderKind};
use taskrx_desktop_core::parser::{
    PhraseReading, is_savable_draft, read_phrase, with_kind, with_name,
};
use taskrx_desktop_core::snooze::{snooze_minutes_or_default, snooze_until};
use taskrx_desktop_core::sync::push::PushCall;
use taskrx_desktop_core::timeline::TimelineRow;
use taskrx_desktop_service::connection::{Event, Table};
use taskrx_desktop_service::data::{Data, Lists};
use taskrx_desktop_service::screens::backlog::{RECORD_AGAIN_MS, last_record};
use taskrx_desktop_service::screens::timing::{
    GapUnit, IntervalUnit, TimingForm, TimingKind, form_for, read_form,
};
use taskrx_desktop_service::screens::today::{Row, Today, TodayInput, today};
use taskrx_desktop_service::writes::{Answer, Moment, Refusal, answer_call, take_back_record};
use taskrx_desktop_service::writes::{
    NewReminder, ReminderPatch, reminder_create, reminder_patch_call,
};

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
    Add,
    Reminders,
}

/// The Add screen: the phrase typed, and once read, the draft with its editable fields.
pub struct Adding {
    pub phrase: TextEdit,
    pub reading: Option<PhraseReading>,
    pub name: TextEdit,
    pub dosage: TextEdit,
    pub instructions: TextEdit,
    /// The ticket of the save in flight, so a second press cannot write a second Reminder.
    pub saving: Option<u64>,
}

impl Default for Adding {
    fn default() -> Self {
        Adding {
            phrase: TextEdit::new(""),
            reading: None,
            name: TextEdit::new(""),
            dosage: TextEdit::new(""),
            instructions: TextEdit::new(""),
            saving: None,
        }
    }
}

/// "Give it a time" on a Backlog item: which item, and the picker's fields as typed.
pub struct Timing {
    /// The item whose picker is open; one at a time.
    pub reminder: Option<String>,
    pub kind: TimingKind,
    pub times: TextEdit,
    pub days_of_week: Vec<i32>,
    pub days_of_month: TextEdit,
    pub every: TextEdit,
    pub unit: IntervalUnit,
    pub date: TextEdit,
    pub at: TextEdit,
    pub gap: TextEdit,
    pub gap_unit: GapUnit,
    pub limit: TextEdit,
}

impl Timing {
    fn closed() -> Timing {
        Timing::starting(None, form_for(TimingKind::Daily, 0, &TimeZone::UTC))
    }

    fn starting(reminder: Option<String>, f: TimingForm) -> Timing {
        Timing {
            reminder,
            kind: f.kind,
            times: TextEdit::new(f.times),
            days_of_week: f.days_of_week,
            days_of_month: TextEdit::new(f.days_of_month),
            every: TextEdit::new(f.every),
            unit: f.unit,
            date: TextEdit::new(f.date),
            at: TextEdit::new(f.at),
            gap: TextEdit::new(f.gap),
            gap_unit: f.gap_unit,
            limit: TextEdit::new(f.limit),
        }
    }

    /// The fields as the service reads them.
    pub fn form(&self) -> TimingForm {
        TimingForm {
            kind: self.kind,
            times: self.times.text.clone(),
            days_of_week: self.days_of_week.clone(),
            days_of_month: self.days_of_month.text.clone(),
            every: self.every.text.clone(),
            unit: self.unit,
            date: self.date.text.clone(),
            at: self.at.text.clone(),
            gap: self.gap.text.clone(),
            gap_unit: self.gap_unit,
            limit: self.limit.text.clone(),
        }
    }
}

/// How long after a press-twice button asks that a press counts as the answer. A double-click
/// lands both presses well inside it; a person who reads the question takes longer.
pub const SETTLE_MS: i64 = 1_000;

/// A press on a button that asks first. The first press arms it (`slot` names the item) and
/// does nothing else; a press within [`SETTLE_MS`] of that is the same double-click and is
/// ignored; a later press is the answer.
fn confirmed(slot: &mut Option<String>, armed_at: &mut i64, id: &str, now: i64) -> bool {
    if slot.as_deref() != Some(id) {
        *slot = Some(id.to_string());
        *armed_at = now;
        return false;
    }
    if now - *armed_at < SETTLE_MS {
        return false;
    }
    *slot = None;
    true
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

/// The second of two writes: sent once the first has landed.
#[derive(Debug, Clone, PartialEq)]
pub struct FollowUp {
    pub call: PushCall,
    pub key: String,
    pub done: String,
    /// Said instead of the plain failure if this write fails.
    pub if_failed: Option<String>,
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
    pub adding: Adding,
    /// The Reminder whose Stop was pressed once: a second press stops it for good.
    pub confirm_stop: Option<String>,
    /// When the last press-twice button was armed: a second press sooner than [`SETTLE_MS`]
    /// after is the same double-click, not an answer to the question.
    pub armed_at: i64,
    pub kind: Option<ReminderKind>,
    pub batch: Option<Batch>,
    /// Writes that have waited long enough to say so.
    pub slow: HashSet<u64>,
    /// A write to send once the ticket's write has landed, under the same key, and what to say
    /// if that second write fails.
    pub follow_ups: HashMap<u64, FollowUp>,
    /// What to say when a write fails, where the plain words would hide what it left behind.
    pub failure_words: HashMap<u64, String>,
    /// Backlog items recorded from this window: the Reminder and the instant of the press. The
    /// list carrying the record can land after the write's answer, so this keeps the row
    /// "just recorded" in between.
    pub recorded: HashMap<String, i64>,
    /// The Backlog item whose Record again was pressed once: a second press records it.
    pub confirm_again: Option<String>,
    pub timing: Timing,
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
            adding: Adding::default(),
            confirm_stop: None,
            armed_at: 0,
            kind: None,
            batch: None,
            slow: HashSet::new(),
            follow_ups: HashMap::new(),
            failure_words: HashMap::new(),
            recorded: HashMap::new(),
            confirm_again: None,
            timing: Timing::closed(),
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

    /// What a row waiting on a write says: "Saving…", or "Still sending…" once it is slow.
    pub fn waiting_word(&self, key: &str) -> &'static str {
        let slow = self
            .sending
            .iter()
            .any(|(t, (k, _))| k == key && self.slow.contains(t));
        if slow {
            "Still sending…"
        } else {
            "Saving…"
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
            Event::Slow { ticket } => {
                self.slow.insert(ticket);
            }
            Event::Wrote { ticket, result } => {
                let (key, what) = self.sending.remove(&ticket).unwrap_or_default();
                self.slow.remove(&ticket);
                let failure = self.failure_words.remove(&ticket);
                if result.is_err()
                    && let Some(reminder) = key
                        .strip_prefix("backlog-")
                        .or_else(|| key.strip_prefix("as-needed-"))
                {
                    // Not recorded after all: the row offers a plain record again.
                    self.recorded.remove(reminder);
                }
                if self.adding.saving == Some(ticket) {
                    self.adding.saving = None;
                    if result.is_ok() {
                        // Saved: the form starts over, and the day shows the new Reminder.
                        self.adding = Adding::default();
                        self.screen = Screen::Today;
                    }
                }
                if let Some(next) = self.follow_ups.remove(&ticket) {
                    match result {
                        Ok(()) => {
                            if let Some(t) = self.send_call(next.call, next.key, next.done)
                                && let Some(words) = next.if_failed
                            {
                                self.failure_words.insert(t, words);
                            }
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
                    Err(e) => {
                        let words = failure.unwrap_or_else(|| format!("Couldn't save that: {e}"));
                        self.say(words, true)
                    }
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
        self.slow.clear();
        self.follow_ups.clear();
        self.failure_words.clear();
        self.recorded.clear();
        self.confirm_again = None;
        self.timing = Timing::closed();
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

    /// Records an as-needed dose as taken now. A dose taken in the last few minutes, here or on
    /// the phone, makes the first press only ask; the next press records another.
    pub fn take_as_needed(&mut self, reminder_id: &str, name: &str) {
        if !self.armed_to_record(reminder_id) {
            return;
        }
        let now = self.now();
        let moment = Moment {
            reminder_id,
            scheduled_time: now,
            instance_id: None,
        };
        let key = format!("as-needed-{reminder_id}");
        if self
            .send(
                &moment,
                Answer::Take { at: now },
                key,
                format!("{name} recorded as taken"),
            )
            .is_some()
        {
            self.recorded.insert(reminder_id.to_string(), now);
        }
    }

    /// Whether a press may record: yes unless something was recorded for this reminder a few
    /// minutes ago and this is not the second press that answers "again?". A first press arms.
    fn armed_to_record(&mut self, reminder_id: &str) -> bool {
        if self.recently_recorded(reminder_id).is_none() {
            self.confirm_again = None;
            return true;
        }
        let now = self.now();
        confirmed(
            &mut self.confirm_again,
            &mut self.armed_at,
            reminder_id,
            now,
        )
    }

    /// Whether a press on Stop stops: the first press only asks "Stop for good?".
    pub fn stop_confirmed(&mut self, reminder_id: &str) -> bool {
        let now = self.now();
        confirmed(&mut self.confirm_stop, &mut self.armed_at, reminder_id, now)
    }

    /// Shows another screen. A half-made second press does not follow the person there.
    pub fn go(&mut self, screen: Screen) {
        self.screen = screen;
        self.confirm_again = None;
        self.confirm_stop = None;
    }

    /// When a Backlog item or an as-needed dose was last recorded, if that was in the last few
    /// minutes: by this window, or by anything the server has, from any device.
    pub fn recently_recorded(&self, reminder_id: &str) -> Option<i64> {
        let now = self.now();
        let here = self
            .recorded
            .get(reminder_id)
            .copied()
            .filter(|at| now - at < RECORD_AGAIN_MS);
        let server = self
            .data
            .as_ref()
            .and_then(|data| last_record(data, reminder_id))
            .map(|(at, _)| at)
            .filter(|at| now - at < RECORD_AGAIN_MS);
        here.max(server)
    }

    /// Records a Backlog item: one Instance at the moment of the act (ADR 0002). The item stays
    /// in the Backlog, so a press a few minutes after the last record only asks; the next press
    /// records it again.
    pub fn complete_backlog(&mut self, reminder_id: &str, name: &str, medication: bool) {
        if !self.armed_to_record(reminder_id) {
            return;
        }
        let now = self.now();
        let moment = Moment {
            reminder_id,
            scheduled_time: now,
            instance_id: None,
        };
        let key = format!("backlog-{reminder_id}");
        let done = if medication {
            format!("{name} recorded as taken")
        } else {
            format!("{name} recorded as done")
        };
        if self
            .send(&moment, Answer::Take { at: now }, key, done)
            .is_some()
        {
            self.recorded.insert(reminder_id.to_string(), now);
        }
    }

    /// Opens "Give it a time" on a Backlog item, daily at 9 to start; pressed again, closes it.
    pub fn toggle_timing(&mut self, reminder_id: &str) {
        if self.timing.reminder.as_deref() == Some(reminder_id) {
            self.timing = Timing::closed();
        } else {
            let f = form_for(TimingKind::Daily, self.now(), &self.zone);
            self.timing = Timing::starting(Some(reminder_id.to_string()), f);
        }
    }

    /// Another variant: a fresh start for it, as on the phone, since the variants share no field.
    /// As needed only for a medication.
    pub fn set_timing_kind(&mut self, kind: TimingKind) {
        if kind == TimingKind::AsNeeded && !self.timing_is_medication() {
            return;
        }
        let f = form_for(kind, self.now(), &self.zone);
        self.timing = Timing::starting(self.timing.reminder.take(), f);
    }

    /// Whether the item the picker is open on is a medication.
    fn timing_is_medication(&self) -> bool {
        let id = self.timing.reminder.as_deref();
        self.data
            .as_ref()
            .zip(id)
            .and_then(|(data, id)| data.reminder(id))
            .is_some_and(|r| r.kind == ReminderKind::Medication)
    }

    /// Gives the open Backlog item the schedule the picker reads, which moves it to the timeline,
    /// or for As needed, to Today's As needed list.
    pub fn move_to_timeline(&mut self) {
        let Some(id) = self.timing.reminder.clone() else {
            return;
        };
        let key = format!("backlog-{id}");
        if self.in_flight(&key) {
            return;
        }
        if self.timing.kind == TimingKind::AsNeeded && !self.timing_is_medication() {
            self.say("Only a medication can be taken as needed", true);
            return;
        }
        let now = self.now();
        let schedule = match read_form(&self.timing.form(), now, &self.zone) {
            Ok(schedule) => schedule,
            Err(problem) => {
                self.say(problem.words(), true);
                return;
            }
        };
        let Some(data) = self.data.as_ref() else {
            self.say("Not connected", true);
            return;
        };
        let name = data
            .reminder(&id)
            .map(|r| r.name.clone())
            .unwrap_or_default();
        let patch = ReminderPatch {
            schedule: Some(schedule),
            ..ReminderPatch::default()
        };
        match reminder_patch_call(data, &id, &patch, now) {
            Ok(call) => {
                let done = if self.timing.kind == TimingKind::AsNeeded {
                    format!("{name} is now taken as needed: Take now is on Today")
                } else {
                    format!("{name} moved to your timeline")
                };
                if self.send_call(call, key, done).is_some() {
                    self.timing = Timing::closed();
                }
            }
            Err(refusal) => self.refuse(&refusal),
        }
    }

    /// Takes back a record made by mistake, a Backlog item's or an as-needed dose: reopened,
    /// then skipped. `key` is the row's, so the row shows the write in flight.
    pub fn undo_record(&mut self, key: String, reminder_id: &str, instance_id: &str, name: &str) {
        if self.in_flight(&key) {
            return;
        }
        let Some(data) = self.data.as_ref() else {
            self.say("Not connected", true);
            return;
        };
        match take_back_record(data, instance_id) {
            Ok((reopen, skip)) => {
                self.confirm_again = None;
                self.recorded.remove(reminder_id);
                if let Some(ticket) = self.send_call(reopen, key.clone(), String::new()) {
                    self.follow_ups.insert(
                        ticket,
                        FollowUp {
                            call: skip,
                            key,
                            done: format!("{name} taken back"),
                            if_failed: Some(format!(
                                "{name} was reopened but not marked skipped: it may show as due"
                            )),
                        },
                    );
                }
            }
            Err(refusal) => self.refuse(&refusal),
        }
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
            self.follow_ups.insert(
                ticket,
                FollowUp {
                    call: then,
                    key,
                    done,
                    if_failed: None,
                },
            );
        }
    }

    /// Reads the typed phrase into a draft. The clock is read now: it anchors "every N hours".
    pub fn read_phrase(&mut self) {
        let phrase = self.adding.phrase.text.clone();
        if phrase.trim().is_empty() {
            return;
        }
        let reading = read_phrase(&phrase, self.now());
        self.adding.name.replace_text(reading.draft.name.clone());
        self.adding
            .dosage
            .replace_text(reading.draft.dosage.clone().unwrap_or_default());
        self.adding
            .instructions
            .replace_text(reading.draft.instructions.clone().unwrap_or_default());
        self.adding.reading = Some(reading);
    }

    /// The draft with the fields as typed: a changed name drops an RxNorm id with it.
    pub fn draft(&self) -> Option<taskrx_desktop_core::parser::ReminderDraft> {
        let reading = self.adding.reading.as_ref()?;
        let mut draft = reading.draft.clone();
        if self.adding.name.text != draft.name {
            draft = with_name(&draft, &self.adding.name.text);
        }
        let text = |e: &TextEdit| Some(e.text.clone()).filter(|t| !t.trim().is_empty());
        draft.dosage = text(&self.adding.dosage);
        draft.instructions = text(&self.adding.instructions);
        Some(draft)
    }

    pub fn set_kind(&mut self, kind: ReminderKind) {
        if let Some(reading) = self.adding.reading.as_mut() {
            reading.draft = with_kind(&reading.draft, kind);
            self.adding
                .dosage
                .replace_text(reading.draft.dosage.clone().unwrap_or_default());
        }
    }

    pub fn can_save(&self) -> bool {
        self.adding.saving.is_none() && self.draft().is_some_and(|d| is_savable_draft(&d))
    }

    pub fn save_new(&mut self) {
        if !self.can_save() {
            return;
        }
        let (Some(draft), Some(user)) = (self.draft(), self.user_id()) else {
            self.say("Not signed in", true);
            return;
        };
        let new = NewReminder::from_draft(&draft);
        let done = match new.kind {
            ReminderKind::Medication => format!("{} added", new.name),
            ReminderKind::Task => "Task added".to_string(),
        };
        match reminder_create(&user, &new, self.now()) {
            Ok((_, call)) => {
                self.adding.saving = self.send_call(call, "adding".into(), done);
            }
            Err(refusal) => self.refuse(&refusal),
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
        ui.follow_ups.insert(
            7,
            FollowUp {
                call: then,
                key: "notice-n1".into(),
                done: "Moved".into(),
                if_failed: None,
            },
        );
        ui.on_event(Event::Wrote {
            ticket: 7,
            result: Err("Reminder not found".into()),
        });
        assert!(ui.follow_ups.is_empty());
        assert!(!ui.in_flight("notice-n1"));
        assert!(ui.toast.as_ref().unwrap().failed);
    }

    #[test]
    fn a_slow_write_keeps_its_row_waiting() {
        let mut ui = ui();
        ui.sending.insert(9, ("row-r1-1".into(), "Taken".into()));
        ui.on_event(Event::Slow { ticket: 9 });
        assert!(ui.in_flight("row-r1-1"));
        assert_eq!(ui.waiting_word("row-r1-1"), "Still sending…");
        ui.on_event(Event::Wrote {
            ticket: 9,
            result: Ok(()),
        });
        assert!(!ui.in_flight("row-r1-1"));
        assert!(ui.slow.is_empty());
    }

    #[test]
    fn a_list_arriving_means_connected() {
        let mut ui = ui();
        ui.on_event(Event::List(Table::Notices, json!([])));
        assert!(ui.connected);
    }

    const NOW: i64 = 1_795_548_600_000;

    /// A Backlog medication recorded `ago` ms before NOW, or never.
    fn backlog_ui(ago: Option<i64>) -> Ui {
        ui_with("medication", json!({ "type": "none" }), ago)
    }

    /// One reminder, r1 "Meclizine", completed `ago` before now if given.
    fn ui_with(kind: &str, schedule: serde_json::Value, ago: Option<i64>) -> Ui {
        let mut ui = ui();
        ui.fixed_now = Some(NOW);
        let instances: Vec<serde_json::Value> = ago
            .map(|ago| {
                let at = (NOW - ago) as f64;
                json!({ "_id": "srv_i1", "localId": "i1", "userId": "u", "reminderId": "srv_r1",
                        "reminderKind": "medication", "scheduledTime": at, "status": "completed",
                        "completedAt": at, "snoozeCount": 0.0, "createdAt": at, "updatedAt": at })
            })
            .into_iter()
            .collect();
        ui.on_event(Event::List(
            Table::Reminders,
            json!([{ "_id": "srv_r1", "localId": "r1", "userId": "u", "kind": kind,
                     "name": "Meclizine", "schedule": schedule, "priority": "normal",
                     "active": true, "createdAt": 1.0, "updatedAt": 1.0 }]),
        ));
        ui.on_event(Event::List(Table::Instances, json!(instances)));
        ui
    }

    #[test]
    fn a_backlog_item_recorded_minutes_ago_asks_before_recording_again() {
        let mut ui = backlog_ui(Some(60_000));
        assert_eq!(ui.recently_recorded("r1"), Some(NOW - 60_000));
        ui.complete_backlog("r1", "Meclizine", true);
        assert_eq!(ui.confirm_again.as_deref(), Some("r1"));
        assert!(ui.toast.is_none(), "the first press only asks");
        // The second press, once the question has been read, goes on to send; with no
        // connection here that is said.
        ui.fixed_now = Some(NOW + SETTLE_MS);
        ui.complete_backlog("r1", "Meclizine", true);
        assert!(ui.confirm_again.is_none());
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }

    #[test]
    fn a_backlog_item_recorded_long_ago_records_on_the_first_press() {
        let mut ui = backlog_ui(Some(RECORD_AGAIN_MS));
        assert_eq!(ui.recently_recorded("r1"), None);
        ui.complete_backlog("r1", "Meclizine", true);
        assert!(ui.confirm_again.is_none());
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }

    #[test]
    fn a_record_from_this_window_counts_before_the_server_shows_it() {
        let mut ui = backlog_ui(None);
        assert_eq!(ui.recently_recorded("r1"), None);
        ui.recorded.insert("r1".into(), NOW - 2_000);
        assert_eq!(ui.recently_recorded("r1"), Some(NOW - 2_000));
        ui.complete_backlog("r1", "Meclizine", true);
        assert_eq!(ui.confirm_again.as_deref(), Some("r1"));
    }

    #[test]
    fn a_failed_backlog_record_is_forgotten() {
        let mut ui = backlog_ui(None);
        ui.recorded.insert("r1".into(), NOW);
        ui.sending.insert(
            5,
            ("backlog-r1".into(), "Meclizine recorded as taken".into()),
        );
        ui.on_event(Event::Wrote {
            ticket: 5,
            result: Err("x".into()),
        });
        assert!(ui.recorded.is_empty());
    }

    #[test]
    fn another_screen_drops_a_half_made_second_press() {
        let mut ui = backlog_ui(Some(1_000));
        ui.complete_backlog("r1", "Meclizine", true);
        assert!(ui.confirm_again.is_some());
        ui.go(Screen::Today);
        assert!(ui.confirm_again.is_none());
    }

    #[test]
    fn a_write_that_leaves_something_behind_says_so_when_it_fails() {
        let mut ui = ui();
        ui.sending
            .insert(8, ("backlog-r1".into(), "taken back".into()));
        ui.failure_words
            .insert(8, "reopened but not marked skipped".into());
        ui.on_event(Event::Wrote {
            ticket: 8,
            result: Err("x".into()),
        });
        assert_eq!(
            ui.toast.as_ref().unwrap().text,
            "reopened but not marked skipped"
        );
        assert!(ui.failure_words.is_empty());
    }

    #[test]
    fn give_it_a_time_opens_on_one_item_and_starts_daily_at_nine() {
        let mut ui = backlog_ui(None);
        ui.toggle_timing("r1");
        assert_eq!(ui.timing.reminder.as_deref(), Some("r1"));
        assert_eq!(ui.timing.kind, TimingKind::Daily);
        assert_eq!(ui.timing.times.text, "9:00 AM");
        ui.set_timing_kind(TimingKind::Once);
        assert_eq!(ui.timing.reminder.as_deref(), Some("r1"));
        assert_eq!(ui.timing.date.text, "today");
        assert_eq!(ui.timing.at.text, "3:30 PM", "an hour on from 2:30");
        ui.toggle_timing("r1");
        assert!(ui.timing.reminder.is_none());
    }

    #[test]
    fn an_unreadable_time_is_said_and_nothing_is_sent() {
        let mut ui = backlog_ui(None);
        ui.toggle_timing("r1");
        ui.timing.times.replace_text("nine-ish".to_string());
        ui.move_to_timeline();
        assert!(
            ui.toast
                .as_ref()
                .unwrap()
                .text
                .starts_with("Type one or more times")
        );
        assert!(ui.sending.is_empty());
        assert_eq!(
            ui.timing.reminder.as_deref(),
            Some("r1"),
            "the picker stays open"
        );
    }

    #[test]
    fn without_a_connection_nothing_is_sent() {
        let mut ui = ui();
        ui.take_as_needed("r1", "Advil");
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }

    fn as_needed() -> serde_json::Value {
        json!({ "type": "asNeeded", "minGapMinutes": 240.0 })
    }

    #[test]
    fn a_dose_taken_minutes_ago_on_any_device_asks_before_take_now_records_another() {
        // Taken two minutes ago, by the server's record alone: nothing stamped in this window.
        let mut ui = ui_with("medication", as_needed(), Some(2 * 60_000));
        assert!(ui.recorded.is_empty());
        ui.take_as_needed("r1", "Meclizine");
        assert_eq!(ui.confirm_again.as_deref(), Some("r1"));
        assert!(ui.toast.is_none(), "the first press only asks");
        assert!(ui.sending.is_empty());
        ui.fixed_now = Some(NOW + SETTLE_MS);
        ui.take_as_needed("r1", "Meclizine");
        assert!(ui.confirm_again.is_none());
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }

    #[test]
    fn a_dose_taken_long_ago_is_taken_on_the_first_press() {
        let mut ui = ui_with("medication", as_needed(), Some(RECORD_AGAIN_MS));
        ui.take_as_needed("r1", "Meclizine");
        assert!(ui.confirm_again.is_none());
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }

    #[test]
    fn a_failed_take_now_is_forgotten() {
        let mut ui = ui_with("medication", as_needed(), None);
        ui.recorded.insert("r1".into(), NOW);
        ui.sending
            .insert(6, ("as-needed-r1".into(), "recorded as taken".into()));
        ui.on_event(Event::Wrote {
            ticket: 6,
            result: Err("x".into()),
        });
        assert!(ui.recorded.is_empty());
    }

    #[test]
    fn as_needed_is_offered_for_a_medication() {
        let mut ui = backlog_ui(None);
        ui.toggle_timing("r1");
        ui.set_timing_kind(TimingKind::AsNeeded);
        assert_eq!(ui.timing.kind, TimingKind::AsNeeded);
        assert_eq!(ui.timing.gap.text, "4");
        assert_eq!(ui.timing.gap_unit, GapUnit::Hours);
        assert_eq!(ui.timing.limit.text, "");
    }

    #[test]
    fn a_task_is_never_made_as_needed() {
        let mut ui = ui_with("task", json!({ "type": "none" }), None);
        ui.toggle_timing("r1");
        ui.set_timing_kind(TimingKind::AsNeeded);
        assert_eq!(ui.timing.kind, TimingKind::Daily, "the press is ignored");
        // Even if the kind were set some other way, nothing is sent.
        ui.timing.kind = TimingKind::AsNeeded;
        ui.move_to_timeline();
        assert_eq!(
            ui.toast.as_ref().unwrap().text,
            "Only a medication can be taken as needed"
        );
        assert!(ui.sending.is_empty());
    }

    #[test]
    fn a_double_click_on_a_press_twice_button_only_asks() {
        let mut ui = ui_with("medication", as_needed(), Some(60_000));
        ui.take_as_needed("r1", "Meclizine");
        ui.fixed_now = Some(NOW + 150);
        ui.take_as_needed("r1", "Meclizine");
        assert_eq!(ui.confirm_again.as_deref(), Some("r1"), "still asking");
        assert!(ui.toast.is_none(), "nothing sent");
        ui.fixed_now = Some(NOW + SETTLE_MS);
        ui.take_as_needed("r1", "Meclizine");
        assert!(ui.confirm_again.is_none());
        assert_eq!(ui.toast.as_ref().unwrap().text, "Not connected");
    }

    #[test]
    fn a_double_click_on_stop_only_asks() {
        let mut ui = backlog_ui(None);
        assert!(!ui.stop_confirmed("r1"));
        assert_eq!(ui.confirm_stop.as_deref(), Some("r1"));
        ui.fixed_now = Some(NOW + 150);
        assert!(!ui.stop_confirmed("r1"), "the same double-click");
        ui.fixed_now = Some(NOW + SETTLE_MS);
        assert!(ui.stop_confirmed("r1"));
        assert!(ui.confirm_stop.is_none());
    }
}
