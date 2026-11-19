//! TaskRx on the desktop: a Companion that shows and answers; the phone delivers (ADR 0016).
//!
//! ```sh
//! cargo run -p taskrx-desktop                              # the window
//! cargo run -p taskrx-desktop -- --snap day.png --scene day # made-up data, no network
//! ```

mod host;
mod theme;
mod ui;
mod view;
mod words;

use std::time::Duration;

use rune::{App, AppSpec, Key, KeyEvent, Tx};
use serde_json::Value;
use taskrx_desktop_service::connection::{Event, Table};

use crate::host::Host;
use crate::ui::{Phase, Ui};

const SCENE_DAY: &str = include_str!("../scenes/day.json");

/// The made-up day the headless scenes draw: signed in, connected, no host.
fn scene_day(s: &mut Ui) {
    let day: Value = serde_json::from_str(SCENE_DAY).expect("the scene's JSON");
    s.fixed_now = day["now"].as_i64();
    if let Some(zone) = day["zone"]
        .as_str()
        .and_then(|z| jiff::tz::TimeZone::get(z).ok())
    {
        s.zone = zone;
    }
    s.phase = Phase::SignedIn;
    s.on_event(Event::Connected(true));
    s.on_event(Event::List(Table::Reminders, day["reminders"].clone()));
    s.on_event(Event::List(Table::Instances, day["instances"].clone()));
    s.on_event(Event::List(Table::Notices, day["notices"].clone()));
}

fn scene_tasks(s: &mut Ui) {
    scene_day(s);
    s.kind = Some(taskrx_desktop_core::domain::ReminderKind::Task);
}

fn scene_all(s: &mut Ui) {
    scene_day(s);
    s.hide_done = false;
}

fn scene_catch_up(s: &mut Ui) {
    scene_day(s);
    s.screen = crate::ui::Screen::CatchUp;
}

fn scene_backlog(s: &mut Ui) {
    scene_day(s);
    s.screen = crate::ui::Screen::Backlog;
}

/// Backlog with Record again pressed once on the item recorded two minutes ago.
fn scene_backlog_again(s: &mut Ui) {
    scene_backlog(s);
    s.confirm_again = Some("r8".to_string());
}

fn scene_notices(s: &mut Ui) {
    scene_day(s);
    s.screen = crate::ui::Screen::Proposals;
}

fn scene_add(s: &mut Ui) {
    scene_day(s);
    s.screen = crate::ui::Screen::Add;
    s.adding.phrase = rune::TextEdit::new("Amoxicillin 500mg three times a day for 10 days");
    s.read_phrase();
}

fn scene_reminders(s: &mut Ui) {
    scene_day(s);
    s.screen = crate::ui::Screen::Reminders;
}

fn scene_signed_out(s: &mut Ui) {
    s.phase = Phase::SignedOut { problem: None };
}

fn scene_offline(s: &mut Ui) {
    scene_day(s);
    s.on_event(Event::Connected(false));
}

fn poll(s: &mut Ui, clock: f64, tx: &mut Tx) -> bool {
    s.clock = clock;
    let mut changed = false;
    if s.toast.as_ref().is_some_and(|t| clock >= t.until) {
        s.toast = None;
        changed = true;
    }
    if s.theme.refresh(true) {
        changed = true;
    }
    // The day moves on its own: the view re-runs each half minute so NEXT, Missed and the
    // as-needed countdown follow the clock, and not on every frame.
    let tick = s.now() / 30_000;
    if tick != s.drawn_tick {
        s.drawn_tick = tick;
        changed = true;
    }
    tx.wake_after(Duration::from_secs(30));
    changed
}

fn on_key(s: &mut Ui, ev: &KeyEvent, tx: &mut Tx) -> bool {
    match ev.key {
        Key::Command('q') | Key::Command('w') => tx.quit(),
        Key::Command('r') => {
            s.theme.refresh(true);
        }
        _ => return false,
    }
    true
}

fn headless() -> bool {
    std::env::args().any(|a| {
        matches!(
            a.as_str(),
            "--snap" | "--tree" | "--script" | "--video" | "--strip" | "--list-scenes" | "--trace"
        )
    })
}

/// One running Companion: two would each refresh the sign-in and spend the other's token. The
/// claim is an abstract socket, so nothing is written to disk.
fn claim_single_instance() -> Option<std::os::unix::net::UnixListener> {
    use std::os::linux::net::SocketAddrExt;
    let addr = std::os::unix::net::SocketAddr::from_abstract_name(b"taskrx-desktop").ok()?;
    std::os::unix::net::UnixListener::bind_addr(&addr).ok()
}

fn main() {
    let spec = AppSpec::<Ui> {
        scenes: &[
            ("day", scene_day),
            ("all", scene_all),
            ("tasks", scene_tasks),
            ("catch-up", scene_catch_up),
            ("backlog", scene_backlog),
            ("backlog-again", scene_backlog_again),
            ("notices", scene_notices),
            ("add", scene_add),
            ("reminders", scene_reminders),
            ("signed-out", scene_signed_out),
            ("offline", scene_offline),
        ],
        poll: Some(poll),
        on_key: Some(on_key),
        min_size: Some((560.0, 420.0)),
        ..AppSpec::new("TaskRx", (760.0, 640.0), || Ui::new(true), view::view)
    };
    // The Wayland app id: it matches the window to taskrx-desktop.desktop and its icon.
    rune::set_app_id("taskrx-desktop");
    let app = App::new(spec);
    if !headless() {
        let Some(_claim) = claim_single_instance() else {
            eprintln!("TaskRx is already running.");
            return;
        };
        let handle = app.handle();
        std::thread::spawn(move || Host::start(handle));
        app.run();
        return;
    }
    app.run();
}
