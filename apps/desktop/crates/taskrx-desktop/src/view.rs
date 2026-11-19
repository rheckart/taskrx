//! What the window draws. It reads [`Ui`] and the service's screen state and forwards presses;
//! it decides nothing about the data.

use rune::text_input;
use rune::tok::{space, type_};
use rune::{Cx, El, ScrollSpec, Tx, col, el, row, text};
use taskrx_desktop_core::domain::{InstanceStatus, ReminderKind};
use taskrx_desktop_core::domain::{
    MoveRefusal, NoticeResolution, ReminderAction, ReminderPhase, reminder_actions, reminder_phase,
};
use taskrx_desktop_core::notices::{ProposalRefusal, ReadDate, UnreadReason};
use taskrx_desktop_core::timeline::TimelineRow;
use taskrx_desktop_service::data::Data;
use taskrx_desktop_service::screens::backlog::{BacklogEntry, backlog};
use taskrx_desktop_service::screens::catch_up::{CatchUpDay, catch_up, total};
use taskrx_desktop_service::screens::proposals::{NoticeView, apply_patch, proposals};
use taskrx_desktop_service::screens::timing::{
    IntervalUnit, TimingKind, in_the_past, read_form, toggle_day,
};
use taskrx_desktop_service::screens::today::{AsNeeded, Row, Today};
use taskrx_desktop_service::writes::Answer;
use taskrx_desktop_service::writes::{
    NewReminder, notice_resolve_call, reminder_action_patch, reminder_create, reminder_patch_call,
};

use crate::theme::Palette;
use crate::ui::{Phase, Screen, Ui, row_key};
use crate::words;

pub fn view(s: &Ui, _cx: &mut Cx<Ui>) -> El {
    let p = s.theme.palette;
    let body = match &s.phase {
        Phase::Starting => centered(p, "TaskRx", "Opening…"),
        Phase::SigningIn => centered(
            p,
            "Finish signing in in your browser",
            "TaskRx is waiting for the browser to come back.",
        )
        .child(
            button(p, "cancel-sign-in", "Cancel", false).on_press(|s: &mut Ui| {
                if let Some(host) = s.host.clone() {
                    host.cancel_sign_in();
                }
                s.phase = Phase::SignedOut { problem: None };
            }),
        ),
        Phase::SignedOut { problem } => sign_in(p, problem.as_deref()),
        Phase::SignedIn => signed_in(s, p),
    };
    let mut root = col().key("window").w_full().h_full().fill(p.bg).child(body);
    if let Some(toast) = &s.toast {
        root = root.child(
            row()
                .key("toast")
                .w_full()
                .px(space::S6)
                .py(space::S2)
                .fill(if toast.failed { p.danger } else { p.card })
                .child(
                    text(toast.text.clone())
                        .font(type_::LABEL)
                        .color(if toast.failed { p.on_accent } else { p.text }),
                ),
        );
    }
    root
}

fn centered(p: Palette, title: &str, line: &str) -> El {
    col()
        .key("centered")
        .w_full()
        .grow(1.0)
        .center()
        .gap(space::S2)
        .child(text(title.to_string()).font(type_::TITLE).color(p.text))
        .child(
            text(line.to_string())
                .font(type_::BODY)
                .color(p.text_secondary),
        )
}

fn button(p: Palette, key: &str, label: &str, primary: bool) -> El {
    row()
        .key(key.to_string())
        .role("button")
        .label(label.to_string())
        .focusable(true)
        .h(28.0)
        .px(space::S3)
        .center()
        .radius(p.radius.min(8.0))
        .fill(if primary { p.accent } else { p.card })
        .border(1.0, if primary { p.accent } else { p.card_border })
        .hover(p.hover)
        .child(
            text(label.to_string())
                .font(type_::LABEL)
                .color(if primary { p.on_accent } else { p.text }),
        )
}

fn sign_in(p: Palette, problem: Option<&str>) -> El {
    let mut c = col()
        .key("sign-in")
        .w_full()
        .grow(1.0)
        .center()
        .gap(space::S3)
        .child(text("TaskRx").font(type_::TITLE).color(p.text))
        .child(
            text("Sign in with the account your phone uses. Your browser will open.")
                .font(type_::BODY)
                .color(p.text_secondary),
        )
        .child(
            button(p, "sign-in-button", "Sign in", true).on_press(|s: &mut Ui| {
                if let Some(host) = s.host.clone() {
                    s.phase = Phase::SigningIn;
                    host.sign_in();
                }
            }),
        );
    if let Some(problem) = problem {
        c = c.child(
            text(problem.to_string())
                .key("sign-in-problem")
                .font(type_::LABEL)
                .color(p.danger),
        );
    }
    c
}

fn signed_in(s: &Ui, p: Palette) -> El {
    let Some(data) = s.data.as_ref() else {
        let line = s
            .problem
            .clone()
            .unwrap_or_else(|| "Reaching the server…".to_string());
        return col()
            .key("loading")
            .w_full()
            .grow(1.0)
            .child(nav(s, p, 0, 0, 0))
            .child(centered(p, "Loading your day", &line));
    };
    let now = s.now();
    let late = catch_up(data, now, &s.zone);
    let loose = backlog(data);
    let notices = proposals(data, now, &s.zone);
    let (sub, mut list) = match s.screen {
        Screen::Today => {
            let today = s.today().expect("data is decoded");
            let list = match s.tab(&today) {
                ReminderKind::Medication => medications(s, p, &today),
                ReminderKind::Task => tasks(s, p, &today),
            };
            (Some(today_header(s, p, &today)), list)
        }
        Screen::CatchUp => (None, catch_up_view(s, p, &late)),
        Screen::Backlog => (None, backlog_view(s, p, &loose)),
        Screen::Proposals => (None, proposals_view(s, p, &notices)),
        Screen::Add => (None, add_view(s, p)),
        Screen::Reminders => (None, reminders_view(s, p, data)),
    };
    // A list that failed after the day was drawn: what is shown may be out of date.
    if let Some(problem) = &s.problem {
        list = col()
            .key("with-problem")
            .w_full()
            .gap(space::S2)
            .child(
                text(problem.clone())
                    .key("problem")
                    .font(type_::LABEL)
                    .color(p.danger),
            )
            .child(list);
    }
    let mut c = col()
        .key("signed-in")
        .w_full()
        .grow(1.0)
        .min_h(0.0)
        .child(nav(s, p, total(&late), loose.len(), notices.len()));
    if let Some(sub) = sub {
        c = c.child(sub);
    }
    c.child(
        col()
            .key("list")
            .w_full()
            .px(space::S6)
            .pb(space::S6)
            .gap(space::S2)
            .child(list)
            .scroll(ScrollSpec {
                stick_bottom: false,
            })
            .grow(1.0)
            .min_h(0.0),
    )
}

fn counted(label: &str, count: usize) -> String {
    if count > 0 {
        format!("{label} {count}")
    } else {
        label.to_string()
    }
}

/// The screens, the connection's state and sign-out.
fn nav(s: &Ui, p: Palette, late: usize, loose: usize, open: usize) -> El {
    let status = if s.auth_problem.is_some() {
        Some("Couldn't renew the sign-in; retrying")
    } else if !s.connected {
        Some("Reconnecting to the server…")
    } else {
        None
    };
    let screen = |key: &str, label: String, which: Screen| {
        button(p, key, &label, s.screen == which)
            .role("tab")
            .on_press(move |s: &mut Ui| s.go(which))
    };
    let mut top = row()
        .key("nav")
        .w_full()
        .px(space::S6)
        .pt(space::S6)
        .pb(space::S2)
        .gap(space::S2)
        .center_y()
        .child(screen("screen-today", "Today".to_string(), Screen::Today))
        .child(screen(
            "screen-catch-up",
            counted("Catch up", late),
            Screen::CatchUp,
        ))
        .child(screen(
            "screen-backlog",
            counted("Backlog", loose),
            Screen::Backlog,
        ))
        .child(screen(
            "screen-proposals",
            counted("Notices", open),
            Screen::Proposals,
        ))
        .child(screen(
            "screen-reminders",
            "Reminders".to_string(),
            Screen::Reminders,
        ))
        .child(screen("screen-add", "Add".to_string(), Screen::Add))
        .child(el().grow(1.0));
    if let Some(line) = status {
        top = top.child(
            text(line)
                .key("connection")
                .font(type_::LABEL)
                .color(p.warning),
        );
    }
    top.child(
        button(p, "sign-out", "Sign out", false).on_press(|s: &mut Ui| {
            if let Some(host) = s.host.clone() {
                host.sign_out();
            }
        }),
    )
}

/// Today's tabs, the day shown and Show done.
fn today_header(s: &Ui, p: Palette, today: &Today) -> El {
    let tab = s.tab(today);
    let mut top = row()
        .key("header")
        .w_full()
        .px(space::S6)
        .pb(space::S3)
        .gap(space::S2)
        .center_y()
        .child(tab_button(
            s,
            p,
            ReminderKind::Medication,
            "Medications",
            today,
        ))
        .child(tab_button(s, p, ReminderKind::Task, "Tasks", today));
    if tab == ReminderKind::Medication {
        top = top
            .child(el().w(space::S3))
            .child(
                button(p, "previous-day", "‹", false)
                    .label("Previous day")
                    .on_press(|s: &mut Ui| s.day_offset -= 1),
            )
            .child(
                text(words::day_heading(
                    today.day_offset,
                    today.day_start,
                    &s.zone,
                ))
                .key("day")
                .font(type_::TITLE)
                .color(p.text),
            );
        if today.day_offset < 0 {
            top = top.child(
                button(p, "next-day", "›", false)
                    .label("Next day")
                    .on_press(|s: &mut Ui| s.day_offset = (s.day_offset + 1).min(0)),
            );
        }
    }
    top.child(el().grow(1.0)).child(
        button(
            p,
            "hide-done",
            if s.hide_done {
                "Show done"
            } else {
                "Hide done"
            },
            false,
        )
        .on_press(|s: &mut Ui| s.hide_done = !s.hide_done),
    )
}

fn catch_up_view(s: &Ui, p: Palette, days: &[CatchUpDay]) -> El {
    let mut c = col().key("catch-up").w_full().gap(space::S2);
    if days.is_empty() {
        return c.child(
            text("Nothing missed in the last week.")
                .key("empty")
                .font(type_::BODY)
                .color(p.text_secondary),
        );
    }
    let all: Vec<TimelineRow> = days.iter().flat_map(|d| d.rows.clone()).collect();
    let mut top = row()
        .key("catch-up-actions")
        .w_full()
        .gap(space::S2)
        .center_y()
        .child(
            text("Answered late, a dose or task is recorded at the time it was due.")
                .font(type_::LABEL)
                .color(p.text_secondary)
                .grow(1.0),
        );
    if let Some(undo) = &s.bulk_undo {
        top = top.child(
            button(p, "undo-late", &format!("Undo: {}", undo.message), false)
                .on_press(|s: &mut Ui| s.undo_late()),
        );
    }
    if s.connected && s.batch.is_none() {
        let (take, skip) = (all.clone(), all);
        top = top
            .child(
                button(p, "take-all", "All taken", true)
                    .on_press(move |s: &mut Ui| s.answer_late(&take, true)),
            )
            .child(
                button(p, "skip-all", "All skipped", false)
                    .on_press(move |s: &mut Ui| s.answer_late(&skip, false)),
            );
    }
    c = c.child(top);
    for day in days {
        let mut heading = row()
            .key(format!("late-day-{}", day.day_start))
            .w_full()
            .gap(space::S2)
            .center_y()
            .child(section(p, "title", &words::day(day.day_start, &s.zone)).grow(1.0));
        if s.connected && s.batch.is_none() && day.rows.len() > 1 {
            let (take, skip) = (day.rows.clone(), day.rows.clone());
            heading = heading
                .child(
                    button(p, "take-day", "Day taken", false)
                        .on_press(move |s: &mut Ui| s.answer_late(&take, true)),
                )
                .child(
                    button(p, "skip-day", "Day skipped", false)
                        .on_press(move |s: &mut Ui| s.answer_late(&skip, false)),
                );
        }
        c = c.child(heading);
        for r in &day.rows {
            c = c.child(late_row(s, p, r));
        }
    }
    c
}

fn late_row(s: &Ui, p: Palette, r: &TimelineRow) -> El {
    let key = row_key(&r.reminder_id, r.scheduled_time);
    let task = r.kind == ReminderKind::Task;
    let mut line = card_row(p, &key, false)
        .child(
            text(words::clock(r.scheduled_time, &s.zone))
                .key("time")
                .font(type_::LABEL)
                .color(p.text_secondary)
                .w(64.0),
        )
        .child(name_column(p, &r.name, r.dosage.as_deref()))
        .child(
            text(if task { "Overdue" } else { "Missed" })
                .key("status")
                .font(type_::LABEL)
                .color(p.danger),
        );
    if !s.connected {
        return line;
    }
    if s.in_flight(&key) {
        return line.child(saving(p, s.waiting_word(&key)));
    }
    if s.batch.is_none() {
        let (take, skip) = (vec![r.clone()], vec![r.clone()]);
        line = line
            .child(
                button(p, "take", if task { "Done" } else { "Taken" }, true)
                    .on_press(move |s: &mut Ui| s.answer_late(&take, true)),
            )
            .child(
                button(p, "skip", "Skipped", false)
                    .on_press(move |s: &mut Ui| s.answer_late(&skip, false)),
            );
    }
    line
}

fn backlog_view(s: &Ui, p: Palette, entries: &[BacklogEntry]) -> El {
    let mut c = col().key("backlog").w_full().gap(space::S2).child(
        text("Things with no set time. Taken or Done records it now, and the item stays here.")
            .font(type_::LABEL)
            .color(p.text_secondary),
    );
    if entries.is_empty() {
        return c.child(
            text("Nothing in the Backlog.")
                .key("empty")
                .font(type_::BODY)
                .color(p.text_secondary),
        );
    }
    let now = s.now();
    for e in entries {
        let r = &e.reminder;
        let key = format!("backlog-{}", r.id);
        let medication = r.kind == ReminderKind::Medication;
        let recent = s.recently_recorded(&r.id);
        let kind = if medication { "Medication" } else { "Task" };
        let last = e.last_done.map_or("never done".to_string(), |t| {
            format!(
                "last done {}, {}",
                words::day(t, &s.zone),
                words::clock(t, &s.zone)
            )
        });
        let mut line =
            card_row(p, &key, recent.is_some()).child(name_column(p, &r.name, r.dosage.as_deref()));
        if recent.is_none() {
            line = line.child(
                text(format!("{kind} · {last}"))
                    .key("last")
                    .font(type_::LABEL)
                    .color(p.text_secondary),
            );
        }
        let timing = s.timing.reminder.as_deref() == Some(r.id.as_str());
        if s.connected {
            let (id, name) = (r.id.clone(), r.name.clone());
            if !s.in_flight(&key) {
                let time_id = id.clone();
                line = line.child(
                    button(
                        p,
                        "time",
                        if timing { "Cancel" } else { "Give it a time" },
                        false,
                    )
                    .on_press(move |s: &mut Ui| s.toggle_timing(&time_id)),
                );
            }
            if s.in_flight(&key) {
                line = line.child(saving(p, s.waiting_word(&key)));
            } else if let Some(at) = recent {
                // The record button's place now holds words, not a button: a press repeated
                // because nothing seemed to happen lands on nothing.
                let confirming = s.confirm_again.as_deref() == Some(r.id.as_str());
                let again = if confirming {
                    "Record again?"
                } else {
                    "Record again"
                };
                let (again_id, again_name) = (id.clone(), name.clone());
                line = line.child(button(p, "again", again, confirming).on_press(
                    move |s: &mut Ui| s.complete_backlog(&again_id, &again_name, medication),
                ));
                if let Some((instance, _)) = e.just_recorded(now) {
                    let instance = instance.to_string();
                    line = line.child(
                        button(p, "undo", "Undo", false)
                            .on_press(move |s: &mut Ui| s.undo_backlog(&id, &instance, &name)),
                    );
                }
                line = line.child(
                    text(format!("Recorded {}", words::clock(at, &s.zone)))
                        .key("recorded")
                        .font(type_::LABEL)
                        .color(p.success),
                );
            } else {
                let label = if medication { "Taken" } else { "Done" };
                line = line.child(
                    button(p, "record", label, true)
                        .on_press(move |s: &mut Ui| s.complete_backlog(&id, &name, medication)),
                );
            }
        }
        c = c.child(line);
        if timing && s.connected {
            c = c.child(timing_view(s, p, &r.id));
        }
    }
    c
}

/// "Give it a time": the variant, its fields, and what they read as before anything is sent.
fn timing_view(s: &Ui, p: Palette, reminder_id: &str) -> El {
    let t = &s.timing;
    let now = s.now();
    let mut c = col()
        .key(format!("timing-{reminder_id}"))
        .w_full()
        .gap(space::S3)
        .px(space::S3)
        .py(space::S3)
        .radius(p.radius)
        .fill(p.card)
        .border(1.0, p.accent);
    let mut kinds = row().key("kinds").gap(space::S2);
    for kind in TimingKind::ALL {
        kinds = kinds.child(
            button(p, kind.label(), kind.label(), t.kind == kind)
                .role("radio")
                .on_press(move |s: &mut Ui| s.set_timing_kind(kind)),
        );
    }
    c = c.child(kinds);
    let times = || field(p, "times", "Times", &t.times, |s| &mut s.timing.times);
    match t.kind {
        TimingKind::Daily => c = c.child(times()),
        TimingKind::Weekly => {
            const DAYS: [&str; 7] = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
            let mut days = row().key("days").gap(space::S2);
            for (d, label) in (0..).zip(DAYS) {
                days = days.child(
                    button(p, label, label, t.days_of_week.contains(&d))
                        .role("checkbox")
                        .on_press(move |s: &mut Ui| {
                            s.timing.days_of_week = toggle_day(&s.timing.days_of_week, d);
                        }),
                );
            }
            c = c.child(days).child(times());
        }
        TimingKind::Monthly => {
            c = c
                .child(field(
                    p,
                    "days-of-month",
                    "Days of the month",
                    &t.days_of_month,
                    |s| &mut s.timing.days_of_month,
                ))
                .child(times());
        }
        TimingKind::Interval => {
            let mut units = row().key("units").gap(space::S2);
            for unit in IntervalUnit::ALL {
                units = units.child(
                    button(p, unit.label(), unit.label(), t.unit == unit)
                        .role("radio")
                        .on_press(move |s: &mut Ui| s.timing.unit = unit),
                );
            }
            c = c
                .child(field(p, "every", "Repeat every", &t.every, |s| {
                    &mut s.timing.every
                }))
                .child(units)
                .child(field(p, "date", "First on", &t.date, |s| {
                    &mut s.timing.date
                }))
                .child(field(p, "at", "At", &t.at, |s| &mut s.timing.at));
        }
        TimingKind::Once => {
            c = c
                .child(field(p, "date", "Day", &t.date, |s| &mut s.timing.date))
                .child(field(p, "at", "Time", &t.at, |s| &mut s.timing.at));
        }
    }
    let reading = read_form(&t.form(), now, &s.zone);
    let (line, color) = match &reading {
        Ok(schedule) => (words::schedule(schedule, &s.zone), p.text),
        Err(problem) => (problem.words().to_string(), p.warning),
    };
    c = c.child(
        text(line)
            .key("reading")
            .font(type_::BODY)
            .color(color)
            .wrap(),
    );
    if reading
        .as_ref()
        .is_ok_and(|schedule| in_the_past(schedule, now))
    {
        c = c.child(
            text("That is in the past: nothing will fire for it.")
                .key("past")
                .font(type_::LABEL)
                .color(p.warning),
        );
    }
    if reading.is_ok() {
        c = c.child(row().key("timing-actions").child(
            button(p, "move", "Move to timeline", true).on_press(|s: &mut Ui| s.move_to_timeline()),
        ));
    }
    c
}

fn proposals_view(s: &Ui, p: Palette, notices: &[NoticeView]) -> El {
    let mut c = col().key("proposals").w_full().gap(space::S2).child(
        text("Messages your watched senders sent. Nothing changes until you choose.")
            .font(type_::LABEL)
            .color(p.text_secondary),
    );
    if notices.is_empty() {
        return c.child(
            text("No notices waiting.")
                .key("empty")
                .font(type_::BODY)
                .color(p.text_secondary),
        );
    }
    for v in notices {
        c = c.child(notice_card(s, p, v));
    }
    c
}

/// What the reader made of the notice: the date it names, or why it found none.
fn reading_line(v: &NoticeView) -> String {
    match &v.reading {
        ReadDate::Found {
            date,
            time,
            matched_text,
        } => {
            let when = date.strftime("%A, %B %-d").to_string();
            match time {
                Some(t) => format!(
                    "Names {when} at {} (\u{201c}{matched_text}\u{201d})",
                    t.strftime("%-I:%M %p")
                ),
                None => format!("Names {when} (\u{201c}{matched_text}\u{201d})"),
            }
        }
        ReadDate::Unread(UnreadReason::NoDate) => "No date found".to_string(),
        ReadDate::Unread(UnreadReason::Ambiguous) => {
            "The date could be read more than one way".to_string()
        }
        ReadDate::Unread(UnreadReason::Competing) => "It names more than one date".to_string(),
        ReadDate::Unread(UnreadReason::Impossible) => {
            "It names a date that doesn't exist".to_string()
        }
    }
}

fn refusal_words(r: ProposalRefusal) -> &'static str {
    match r {
        ProposalRefusal::NoOccurrence => "the task has no moment near that date",
        ProposalRefusal::Move(MoveRefusal::InThePast) => "that time has passed",
        ProposalRefusal::Move(MoveRefusal::AlreadyAnswered) => "that moment is already answered",
        ProposalRefusal::Move(MoveRefusal::InProgress) => "that moment is under way",
        ProposalRefusal::Move(MoveRefusal::InAPause) => "the task is paused then",
        ProposalRefusal::Move(MoveRefusal::TooLate) => "it is too late to move it",
        ProposalRefusal::Move(MoveRefusal::NotATask | MoveRefusal::NotAfter) => {
            "it can't be moved there"
        }
    }
}

fn notice_card(s: &Ui, p: Palette, v: &NoticeView) -> El {
    let n = &v.notice;
    let key = format!("notice-{}", n.id);
    let mut card = col()
        .key(key.clone())
        .w_full()
        .px(space::S3)
        .py(space::S3)
        .gap(space::S2)
        .radius(p.radius)
        .fill(p.card)
        .border(1.0, p.card_border)
        .child(
            row()
                .key("head")
                .w_full()
                .gap(space::S2)
                .child(
                    text(n.title.clone())
                        .font(type_::TITLE)
                        .color(p.text)
                        .grow(1.0)
                        .truncate(),
                )
                .child(
                    text(format!(
                        "{} · {}",
                        n.source_value,
                        words::day(n.received_at, &s.zone)
                    ))
                    .font(type_::LABEL)
                    .color(p.text_secondary),
                ),
        );
    if let Some(sentence) = &n.sentence {
        card = card.child(
            text(sentence.clone())
                .key("sentence")
                .font(type_::BODY)
                .color(p.text)
                .wrap(),
        );
    }
    card = card.child(
        text(reading_line(v))
            .key("reading")
            .font(type_::LABEL)
            .color(p.text_secondary),
    );
    for offer in &v.offers {
        let pr = &offer.proposal;
        let line = match (pr.from, pr.refusal) {
            (Some(from), None) => format!(
                "Move {} from {}, {} to {}, {}",
                offer.task_name,
                words::day(from, &s.zone),
                words::clock(from, &s.zone),
                words::day(pr.to, &s.zone),
                words::clock(pr.to, &s.zone)
            ),
            (_, Some(r)) => format!("{}: can't move, {}", offer.task_name, refusal_words(r)),
            (None, None) => format!("{}: nothing to move", offer.task_name),
        };
        let mut r = row()
            .key(format!("offer-{}", pr.reminder_id))
            .w_full()
            .gap(space::S2)
            .center_y()
            .child(text(line).font(type_::BODY).color(p.text).grow(1.0));
        if s.connected && !s.in_flight(&key) && pr.applies() {
            let (notice_id, proposal, name) = (n.id.clone(), pr.clone(), offer.task_name.clone());
            let key = key.clone();
            r = r.child(
                button(p, "apply", "Move it", true).on_press(move |s: &mut Ui| {
                    let now = s.now();
                    let Some(data) = s.data.as_ref() else { return };
                    let calls = apply_patch(data, &notice_id, &proposal, now).and_then(|patch| {
                        Ok((
                            reminder_patch_call(data, &proposal.reminder_id, &patch, now)?,
                            notice_resolve_call(data, &notice_id, NoticeResolution::Applied, now)?,
                        ))
                    });
                    match calls {
                        Ok((first, then)) => {
                            s.send_then(first, then, key.clone(), format!("{name} moved"))
                        }
                        Err(refusal) => s.refuse(&refusal),
                    }
                }),
            );
        }
        card = card.child(r);
    }
    let mut actions = row()
        .key("actions")
        .w_full()
        .gap(space::S2)
        .child(el().grow(1.0));
    if !s.connected {
        return card;
    }
    if s.in_flight(&key) {
        return card.child(actions.child(saving(p, s.waiting_word(&key))));
    }
    let new_at = v.new_task_at;
    let (notice_id, title, sentence) = (n.id.clone(), n.title.clone(), n.sentence.clone());
    let task_key = key.clone();
    actions = actions.child(
        button(
            p,
            "new-task",
            &format!(
                "New task {}, {}",
                words::day(new_at, &s.zone),
                words::clock(new_at, &s.zone)
            ),
            false,
        )
        .on_press(move |s: &mut Ui| {
            let now = s.now();
            let (Some(user), Some(data)) = (s.user_id(), s.data.as_ref()) else {
                return;
            };
            let name = if title.trim().is_empty() {
                "From a notice".to_string()
            } else {
                title.trim().to_string()
            };
            let new = NewReminder::task(name.clone(), sentence.clone(), new_at);
            let calls = reminder_create(&user, &new, now).and_then(|(_, create)| {
                Ok((
                    create,
                    notice_resolve_call(data, &notice_id, NoticeResolution::NewTask, now)?,
                ))
            });
            match calls {
                Ok((first, then)) => {
                    s.send_then(first, then, task_key.clone(), "Task added".into())
                }
                Err(refusal) => s.refuse(&refusal),
            }
        }),
    );
    let (notice_id, dismiss_key) = (n.id.clone(), key.clone());
    actions = actions.child(
        button(p, "dismiss", "Dismiss", false).on_press(move |s: &mut Ui| {
            let now = s.now();
            let Some(data) = s.data.as_ref() else { return };
            match notice_resolve_call(data, &notice_id, NoticeResolution::Dismissed, now) {
                Ok(call) => {
                    let _ = s.send_call(call, dismiss_key.clone(), "Notice dismissed".into());
                }
                Err(refusal) => s.refuse(&refusal),
            }
        }),
    );
    card.child(actions)
}

/// A labelled one-line text field bound to `field`.
fn field(
    p: Palette,
    key: &str,
    label: &str,
    edit: &rune::TextEdit,
    bind: fn(&mut Ui) -> &mut rune::TextEdit,
) -> El {
    col()
        .key(key.to_string())
        .w_full()
        .gap(4.0)
        .child(
            text(label.to_string())
                .font(type_::SECTION)
                .color(p.text_secondary),
        )
        .child(
            el().key("box")
                .w_full()
                .h(32.0)
                .px(space::S2)
                .radius(p.radius.min(8.0))
                .fill(p.bg)
                .border(1.0, p.card_border)
                .center_y()
                .child(
                    text_input(edit, type_::BODY)
                        .key("input")
                        .single_line()
                        .color(p.text)
                        .on_edit(bind),
                ),
        )
}

fn add_view(s: &Ui, p: Palette) -> El {
    let a = &s.adding;
    let mut c = col()
        .key("add")
        .w_full()
        .gap(space::S3)
        .child(
            text("Type it the way you'd say it: \u{201c}Lisinopril 10mg every morning at 8\u{201d}, \u{201c}water the plants every Tuesday\u{201d}.")
                .font(type_::LABEL)
                .color(p.text_secondary)
                .wrap(),
        )
        .child(
            row()
                .key("phrase-row")
                .w_full()
                .gap(space::S2)
                .center_y()
                .child(
                    el().key("phrase-box")
                        .grow(1.0)
                        .h(36.0)
                        .px(space::S2)
                        .radius(p.radius.min(8.0))
                        .fill(p.card)
                        .border(1.0, p.accent)
                        .center_y()
                        .child(
                            text_input(&a.phrase, type_::BODY)
                                .key("phrase")
                                .single_line()
                                .color(p.text)
                                .placeholder("What to remember, and when", p.text_secondary)
                                .on_edit(|s: &mut Ui| &mut s.adding.phrase)
                                .on_submit(|s: &mut Ui, _| s.read_phrase()),
                        ),
                )
                .child(button(p, "read", "Read it", true).on_press(|s: &mut Ui| s.read_phrase())),
        );
    let Some(reading) = &a.reading else {
        return c;
    };
    let Some(draft) = s.draft() else { return c };
    if !reading.understood || !reading.reasons.is_empty() {
        let line = if reading.understood {
            reading.reasons.join(" ")
        } else {
            format!("I couldn't read all of that. {}", reading.reasons.join(" "))
        };
        c = c.child(
            text(line)
                .key("reasons")
                .font(type_::LABEL)
                .color(p.warning)
                .wrap(),
        );
    }
    let kind = |key: &str, label: &str, k: ReminderKind| {
        button(p, key, label, draft.kind == k)
            .role("tab")
            .on_press(move |s: &mut Ui| s.set_kind(k))
    };
    c = c
        .child(
            row()
                .key("kind")
                .gap(space::S2)
                .child(kind(
                    "kind-medication",
                    "Medication",
                    ReminderKind::Medication,
                ))
                .child(kind("kind-task", "Task", ReminderKind::Task)),
        )
        .child(field(p, "name-field", "Name", &a.name, |s| {
            &mut s.adding.name
        }));
    if draft.kind == ReminderKind::Medication {
        c = c.child(field(p, "dosage-field", "Dose", &a.dosage, |s| {
            &mut s.adding.dosage
        }));
    }
    c = c
        .child(field(
            p,
            "instructions-field",
            "Instructions",
            &a.instructions,
            |s| &mut s.adding.instructions,
        ))
        .child(
            col()
                .key("when")
                .gap(4.0)
                .child(text("When").font(type_::SECTION).color(p.text_secondary))
                .child(
                    text(words::schedule(&draft.schedule, &s.zone))
                        .font(type_::BODY)
                        .color(p.text),
                )
                .child(
                    text("To change when, change the phrase and read it again.")
                        .font(type_::LABEL)
                        .color(p.text_secondary),
                ),
        );
    let mut actions = row()
        .key("save-row")
        .w_full()
        .gap(space::S2)
        .child(el().grow(1.0));
    if !s.connected {
        actions = actions.child(text("Not connected").font(type_::LABEL).color(p.warning));
    } else if a.saving.is_some() {
        actions = actions.child(saving(p, s.waiting_word("adding")));
    } else if s.can_save() {
        actions =
            actions.child(button(p, "save", "Save", true).on_press(|s: &mut Ui| s.save_new()));
    } else {
        actions = actions.child(
            text("It needs a name and a time it can keep")
                .font(type_::LABEL)
                .color(p.text_secondary),
        );
    }
    c.child(actions)
}

fn reminders_view(s: &Ui, p: Palette, data: &Data) -> El {
    let now = s.now();
    let mut list: Vec<_> = data.reminders.iter().filter(|r| r.active).collect();
    list.sort_by_cached_key(|r| (r.kind == ReminderKind::Task, r.name.to_lowercase()));
    let mut c = col().key("reminders").w_full().gap(space::S2);
    if list.is_empty() {
        return c.child(
            text("No reminders yet. Add one.")
                .key("empty")
                .font(type_::BODY)
                .color(p.text_secondary),
        );
    }
    for r in list {
        let key = format!("reminder-{}", r.id);
        let phase = reminder_phase(r, now);
        let (phase_word, phase_color) = match phase {
            ReminderPhase::Paused(_) => ("Paused", p.warning),
            _ => ("", p.text_secondary),
        };
        let mut info = col().key("info").grow(1.0).min_w(0.0).gap(2.0).child(
            text(match &r.dosage {
                Some(d) => format!("{} · {d}", r.name),
                None => r.name.clone(),
            })
            .font(type_::TITLE)
            .color(p.text)
            .truncate(),
        );
        info = info.child(
            text(words::schedule(&r.schedule, &s.zone))
                .font(type_::LABEL)
                .color(p.text_secondary)
                .truncate(),
        );
        let mut line = card_row(p, &key, false).child(info).child(
            text(phase_word)
                .key("phase")
                .font(type_::LABEL)
                .color(phase_color),
        );
        if s.connected {
            if s.in_flight(&key) {
                line = line.child(saving(p, s.waiting_word(&key)));
            } else {
                for action in reminder_actions(r, phase) {
                    let confirming = s.confirm_stop.as_deref() == Some(r.id.as_str());
                    let label = match action {
                        ReminderAction::Pause => "Pause",
                        ReminderAction::Resume => "Resume",
                        ReminderAction::Stop if confirming => "Stop for good?",
                        ReminderAction::Stop => "Stop",
                    };
                    let (id, name, key) = (r.id.clone(), r.name.clone(), key.clone());
                    line = line.child(button(p, action.wire(), label, confirming).on_press(
                        move |s: &mut Ui| {
                            if action == ReminderAction::Stop
                                && s.confirm_stop.as_deref() != Some(id.as_str())
                            {
                                s.confirm_stop = Some(id.clone());
                                return;
                            }
                            s.confirm_stop = None;
                            let now = s.now();
                            let Some(data) = s.data.as_ref() else { return };
                            let Some(reminder) = data.reminder(&id) else {
                                return;
                            };
                            let call = reminder_action_patch(reminder, action, now)
                                .and_then(|patch| reminder_patch_call(data, &id, &patch, now));
                            let done = match action {
                                ReminderAction::Pause => format!("{name} paused"),
                                ReminderAction::Resume => format!("{name} resumed"),
                                ReminderAction::Stop => format!("{name} stopped"),
                            };
                            match call {
                                Ok(call) => {
                                    let _ = s.send_call(call, key.clone(), done);
                                }
                                Err(refusal) => s.refuse(&refusal),
                            }
                        },
                    ));
                }
            }
        }
        c = c.child(line);
    }
    c
}

fn card_row(p: Palette, key: &str, highlighted: bool) -> El {
    row()
        .key(key.to_string())
        .w_full()
        .min_h(48.0)
        .px(space::S3)
        .py(space::S2)
        .gap(space::S3)
        .center_y()
        .radius(p.radius)
        .fill(p.card)
        .border(1.0, if highlighted { p.accent } else { p.card_border })
}

fn name_column(p: Palette, name: &str, dosage: Option<&str>) -> El {
    let mut c = col().key("name").grow(1.0).min_w(0.0).gap(2.0).child(
        text(name.to_string())
            .font(type_::TITLE)
            .color(p.text)
            .truncate(),
    );
    if let Some(dosage) = dosage {
        c = c.child(
            text(dosage.to_string())
                .font(type_::LABEL)
                .color(p.text_secondary)
                .truncate(),
        );
    }
    c
}

fn saving(p: Palette, word: &'static str) -> El {
    text(word)
        .key("saving")
        .font(type_::LABEL)
        .color(p.text_secondary)
}

fn tab_button(s: &Ui, p: Palette, kind: ReminderKind, label: &str, today: &Today) -> El {
    let selected = s.tab(today) == kind;
    let count = today.to_do(kind);
    let label = if count > 0 {
        format!("{label} {count}")
    } else {
        label.to_string()
    };
    let key = match kind {
        ReminderKind::Medication => "tab-medications",
        ReminderKind::Task => "tab-tasks",
    };
    button(p, key, &label, selected)
        .role("tab")
        .on_press(move |s: &mut Ui| s.kind = Some(kind))
}

fn visible(s: &Ui, row: &Row) -> bool {
    !s.hide_done
        || !matches!(
            row.row.status,
            InstanceStatus::Completed | InstanceStatus::Skipped
        )
}

fn medications(s: &Ui, p: Palette, today: &Today) -> El {
    let mut c = col().key("medications").w_full().gap(space::S2);
    if today.day_offset == 0 && !today.as_needed.is_empty() {
        c = c.child(section(p, "as-needed-title", "As needed"));
        for a in &today.as_needed {
            c = c.child(as_needed_row(s, p, a));
        }
        c = c.child(section(p, "day-title", "Scheduled"));
    }
    let rows: Vec<&Row> = today.medications.iter().filter(|r| visible(s, r)).collect();
    if rows.is_empty() {
        let line = if today.medications.is_empty() {
            "Nothing scheduled."
        } else {
            "Everything is answered."
        };
        return c.child(
            text(line)
                .key("empty")
                .font(type_::BODY)
                .color(p.text_secondary),
        );
    }
    for r in rows {
        c = c.child(row_view(s, p, r, false));
    }
    c
}

fn tasks(s: &Ui, p: Palette, today: &Today) -> El {
    let mut c = col().key("tasks").w_full().gap(space::S2);
    let mut any = false;
    let groups: [(&str, &str, &Vec<Row>); 2] = [
        ("overdue-title", "Overdue", &today.overdue),
        ("due-today-title", "Due today", &today.due_today),
    ];
    for (key, title, rows) in groups {
        let shown: Vec<&Row> = rows.iter().filter(|r| visible(s, r)).collect();
        if shown.is_empty() {
            continue;
        }
        any = true;
        c = c.child(section(p, key, title));
        for r in shown {
            c = c.child(row_view(s, p, r, true));
        }
    }
    for day in &today.upcoming {
        let shown: Vec<&Row> = day.rows.iter().filter(|r| visible(s, r)).collect();
        if shown.is_empty() {
            continue;
        }
        any = true;
        c = c.child(section(
            p,
            &format!("upcoming-{}", day.day_start),
            &words::day(day.day_start, &s.zone),
        ));
        for r in shown {
            c = c.child(row_view(s, p, r, true));
        }
    }
    if !any {
        c = c.child(
            text("No tasks waiting.")
                .key("empty")
                .font(type_::BODY)
                .color(p.text_secondary),
        );
    }
    c
}

fn section(p: Palette, key: &str, title: &str) -> El {
    text(title.to_string())
        .key(key.to_string())
        .font(type_::SECTION)
        .color(p.text_secondary)
}

fn row_view(s: &Ui, p: Palette, r: &Row, task: bool) -> El {
    let key = row_key(&r.row.reminder_id, r.row.scheduled_time);
    let status_word = if task {
        words::task_status(r.shown)
    } else {
        words::status(r.shown)
    };
    let status_color = match r.shown {
        InstanceStatus::Completed => p.success,
        InstanceStatus::Missed => p.danger,
        InstanceStatus::Snoozed => p.warning,
        _ => p.text_secondary,
    };
    let mut name = col().key("name").grow(1.0).min_w(0.0).gap(2.0).child(
        text(r.row.name.clone())
            .font(type_::TITLE)
            .color(p.text)
            .truncate(),
    );
    if let Some(dosage) = &r.row.dosage {
        name = name.child(
            text(dosage.clone())
                .font(type_::LABEL)
                .color(p.text_secondary)
                .truncate(),
        );
    }
    let mut line = row()
        .key(key.clone())
        .w_full()
        .min_h(48.0)
        .px(space::S3)
        .py(space::S2)
        .gap(space::S3)
        .center_y()
        .radius(p.radius)
        .fill(p.card)
        .border(
            1.0,
            if r.row.is_next {
                p.accent
            } else {
                p.card_border
            },
        )
        .child(
            text(words::clock(r.row.scheduled_time, &s.zone))
                .key("time")
                .font(type_::LABEL)
                .color(p.text_secondary)
                .w(64.0),
        )
        .child(name);
    if r.row.is_next {
        line = line.child(
            text("NEXT")
                .key("next")
                .font(type_::SECTION)
                .color(p.accent),
        );
    }
    line = line.child(
        text(status_word)
            .key("status")
            .font(type_::LABEL)
            .color(status_color),
    );
    let (take, take_done) = if task {
        ("Done", "done")
    } else {
        ("Take", "taken")
    };
    // Without the server there is nothing to answer with (ADR 0016): the rows only show.
    if !s.connected {
        return line;
    }
    // A row whose answer is on its way offers nothing until the server has it.
    if s.in_flight(&key) {
        return line.child(saving(p, s.waiting_word(&key)));
    }
    if r.is_answerable() {
        let now_row = r.clone();
        let snooze_row = r.clone();
        let skip_row = r.clone();
        let name = r.row.name.clone();
        let snooze_name = name.clone();
        let skip_name = name.clone();
        line = line.child(button(p, "take", take, true).on_press_tx(
            move |s: &mut Ui, tx: &mut Tx| {
                s.clock = tx.clock();
                // A task's Done is stamped with when it was due, as on the phone and in
                // Catch up; a dose is taken now.
                let at = if task {
                    now_row.row.scheduled_time
                } else {
                    s.now()
                };
                s.answer(&now_row, Answer::Take { at }, format!("{name} {take_done}"));
            },
        ));
        // A task row answers Done or Skip, as the phone's does; snoozing is a dose's.
        if !task {
            line = line.child(button(p, "snooze", "Snooze", false).on_press_tx(
                move |s: &mut Ui, tx: &mut Tx| {
                    s.clock = tx.clock();
                    let until = s.snooze_until();
                    s.answer(
                        &snooze_row,
                        Answer::Snooze { until },
                        format!("{snooze_name} snoozed"),
                    );
                },
            ));
        }
        line = line.child(button(p, "skip", "Skip", false).on_press_tx(
            move |s: &mut Ui, tx: &mut Tx| {
                s.clock = tx.clock();
                s.answer(&skip_row, Answer::Skip, format!("{skip_name} skipped"));
            },
        ));
    }
    if r.undoable {
        let undo_row = r.clone();
        let name = r.row.name.clone();
        line = line.child(button(p, "undo", "Undo", false).on_press_tx(
            move |s: &mut Ui, tx: &mut Tx| {
                s.clock = tx.clock();
                s.answer(&undo_row, Answer::Reopen, format!("{name} taken back"));
            },
        ));
    }
    line
}

fn as_needed_row(s: &Ui, p: Palette, a: &AsNeeded) -> El {
    let now = s.now();
    let ready = a.status.is_ready(now);
    let line = match (ready, a.status.ready_at, a.status.last_taken_at) {
        (false, Some(at), _) => format!("Next dose at {}", words::clock(at, &s.zone)),
        (true, _, Some(last)) => format!("Ready · last taken {}", words::clock(last, &s.zone)),
        _ => "Ready".to_string(),
    };
    let (id, name) = (a.reminder_id.clone(), a.name.clone());
    let mut text_col = col().key("name").grow(1.0).min_w(0.0).gap(2.0).child(
        text(a.name.clone())
            .font(type_::TITLE)
            .color(p.text)
            .truncate(),
    );
    if let Some(dosage) = &a.dosage {
        text_col = text_col.child(
            text(dosage.clone())
                .font(type_::LABEL)
                .color(p.text_secondary)
                .truncate(),
        );
    }
    let r = row()
        .key(format!("as-needed-{}", a.reminder_id))
        .w_full()
        .min_h(48.0)
        .px(space::S3)
        .py(space::S2)
        .gap(space::S3)
        .center_y()
        .radius(p.radius)
        .fill(p.card)
        .border(1.0, p.card_border)
        .child(text_col)
        .child(
            text(line)
                .key("as-needed-status")
                .font(type_::LABEL)
                .color(if ready { p.success } else { p.text_secondary }),
        );
    if !s.connected {
        return r;
    }
    let as_needed_key = format!("as-needed-{}", a.reminder_id);
    if s.in_flight(&as_needed_key) {
        return r.child(saving(p, s.waiting_word(&as_needed_key)));
    }
    // Offered while waiting too: the wait is advice, and a dose already taken is recorded.
    r.child(
        button(p, "take", "Take now", ready).on_press_tx(move |s: &mut Ui, tx: &mut Tx| {
            s.clock = tx.clock();
            s.take_as_needed(&id, &name);
        }),
    )
}
