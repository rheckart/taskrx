//! What the window draws. It reads [`Ui`] and the service's screen state and forwards presses;
//! it decides nothing about the data.

use rune::tok::{space, type_};
use rune::{Cx, El, ScrollSpec, Tx, col, el, row, text};
use taskrx_desktop_core::domain::{InstanceStatus, ReminderKind};
use taskrx_desktop_service::screens::today::{AsNeeded, Row, Today};
use taskrx_desktop_service::writes::Answer;

use crate::theme::Palette;
use crate::ui::{Phase, Ui, row_key};
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
    let Some(today) = s.today() else {
        let line = s
            .problem
            .clone()
            .unwrap_or_else(|| "Reaching the server…".to_string());
        return col()
            .key("loading")
            .w_full()
            .grow(1.0)
            .child(header(s, p, None))
            .child(centered(p, "Loading your day", &line));
    };
    let tab = s.tab(&today);
    let mut list = match tab {
        ReminderKind::Medication => medications(s, p, &today),
        ReminderKind::Task => tasks(s, p, &today),
    };
    // A list that failed after the day was drawn: the day shown may be out of date.
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
    col()
        .key("today")
        .w_full()
        .grow(1.0)
        .min_h(0.0)
        .child(header(s, p, Some(&today)))
        .child(
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

fn header(s: &Ui, p: Palette, today: Option<&Today>) -> El {
    let mut status = if !s.connected {
        Some(("Reconnecting to the server…", p.warning))
    } else {
        None
    };
    if let Some(why) = &s.auth_problem {
        let _ = why;
        status = Some(("Couldn't renew the sign-in; retrying", p.warning));
    }
    let mut top = row()
        .key("header")
        .w_full()
        .px(space::S6)
        .pt(space::S6)
        .pb(space::S3)
        .gap(space::S2)
        .center_y();
    if let Some(today) = today {
        let tab = s.tab(today);
        top = top
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
    }
    top = top.child(el().grow(1.0));
    if let Some((line, color)) = status {
        top = top.child(text(line).key("connection").font(type_::LABEL).color(color));
    }
    top.child(
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
    .child(
        button(p, "sign-out", "Sign out", false).on_press(|s: &mut Ui| {
            if let Some(host) = s.host.clone() {
                host.sign_out();
            }
        }),
    )
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
        return line.child(
            text("Saving…")
                .key("saving")
                .font(type_::LABEL)
                .color(p.text_secondary),
        );
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
    if s.in_flight(&format!("as-needed-{}", a.reminder_id)) {
        return r.child(
            text("Saving…")
                .key("saving")
                .font(type_::LABEL)
                .color(p.text_secondary),
        );
    }
    // Offered while waiting too: the wait is advice, and a dose already taken is recorded.
    r.child(
        button(p, "take", "Take now", ready).on_press_tx(move |s: &mut Ui, tx: &mut Tx| {
            s.clock = tx.clock();
            s.take_as_needed(&id, &name);
        }),
    )
}
