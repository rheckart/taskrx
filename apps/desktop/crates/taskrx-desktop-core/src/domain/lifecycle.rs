//! The lifecycles of an Instance and a Reminder, and the rules for pausing and moving.
//! Kotlin: `core/domain/InstanceLifecycle.kt`, `ReminderLifecycle.kt`, `Pause.kt`,
//! `OccurrenceOverride.kt`.

use super::enums::{InstanceStatus, ReminderKind, is_answerable};
use super::reminder::{OccurrenceOverride, PauseWindow, Reminder, ReminderInstance, is_paused_at};
use super::schedule::ScheduleTimes;
use crate::sweep::GRACE_PERIOD_MS;

/// What a person may do to an Instance. The sweep's Missed verdict is not an action.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum InstanceAction {
    Complete,
    Skip,
    Snooze,
    /// Take an answer back, returning the row to pending (ADR 0008).
    Undo,
    /// Correct when a completed dose was taken.
    ChangeCompletedTime,
}

impl InstanceAction {
    pub const ALL: [InstanceAction; 5] = [
        Self::Complete,
        Self::Skip,
        Self::Snooze,
        Self::Undo,
        Self::ChangeCompletedTime,
    ];

    pub fn wire(self) -> &'static str {
        match self {
            Self::Complete => "complete",
            Self::Skip => "skip",
            Self::Snooze => "snooze",
            Self::Undo => "undo",
            Self::ChangeCompletedTime => "change-completed-time",
        }
    }

    pub fn from_wire(wire: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|a| a.wire() == wire)
    }
}

/// The answers that can be taken back: a completion, a skip or a snooze. Missed is the app's
/// guess, answered rather than undone.
pub fn is_undoable(status: InstanceStatus) -> bool {
    matches!(
        status,
        InstanceStatus::Completed | InstanceStatus::Skipped | InstanceStatus::Snoozed
    )
}

/// What `status` offers the person, in this order: the three answers while it is open, Undo
/// while it is an answer (a snooze is both), and the taken-time correction when completed.
pub fn available_actions(status: InstanceStatus) -> Vec<InstanceAction> {
    let mut actions = Vec::new();
    if is_answerable(status) {
        actions.extend([
            InstanceAction::Complete,
            InstanceAction::Skip,
            InstanceAction::Snooze,
        ]);
    }
    if is_undoable(status) {
        actions.push(InstanceAction::Undo);
    }
    if status == InstanceStatus::Completed {
        actions.push(InstanceAction::ChangeCompletedTime);
    }
    actions
}

/// The status `action` leaves `from` in, or `None` when the action is not offered.
pub fn action_target(action: InstanceAction, from: InstanceStatus) -> Option<InstanceStatus> {
    if !available_actions(from).contains(&action) {
        return None;
    }
    Some(match action {
        InstanceAction::Complete | InstanceAction::ChangeCompletedTime => InstanceStatus::Completed,
        InstanceAction::Skip => InstanceStatus::Skipped,
        InstanceAction::Snooze => InstanceStatus::Snoozed,
        InstanceAction::Undo => InstanceStatus::Pending,
    })
}

/// Whether a row may be rewritten from `from` (`None`: being created) to `to`. A row may always
/// become what it already is; Completed is final until undone; the sweep never touches an answer.
pub fn allows_instance_transition(from: Option<InstanceStatus>, to: InstanceStatus) -> bool {
    let Some(from) = from else { return true };
    if from == to {
        return true;
    }
    match from {
        InstanceStatus::Pending | InstanceStatus::Snoozed => true,
        InstanceStatus::Skipped => to != InstanceStatus::Missed,
        InstanceStatus::Missed => to != InstanceStatus::Pending,
        InstanceStatus::Completed => to == InstanceStatus::Pending,
    }
}

/// Where a Reminder is in its life, as of some instant.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReminderPhase {
    Active,
    /// No Occurrences until the window ends.
    Paused(PauseWindow),
    /// `active = false`: gone for good, its history kept.
    Stopped,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReminderAction {
    Pause,
    Resume,
    Stop,
}

impl ReminderAction {
    pub fn wire(self) -> &'static str {
        match self {
            Self::Pause => "pause",
            Self::Resume => "resume",
            Self::Stop => "stop",
        }
    }
}

/// The window that has not finished as of `now`: running, or starting later. At most one.
pub fn live_pause(windows: &[PauseWindow], now: i64) -> Option<PauseWindow> {
    windows
        .iter()
        .copied()
        .find(|w| w.until.is_none_or(|u| u > now))
}

/// A pause that cannot be added: one is already live, or it would end before it starts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PauseRefused(pub &'static str);

/// `windows` with a new pause, refused while one is running or waiting to start.
pub fn add_pause(
    windows: &[PauseWindow],
    from: i64,
    until: Option<i64>,
    now: i64,
) -> Result<Vec<PauseWindow>, PauseRefused> {
    if live_pause(windows, now).is_some() {
        return Err(PauseRefused("It is already paused."));
    }
    if until.is_some_and(|u| u <= from) {
        return Err(PauseRefused("A pause must end after it starts."));
    }
    let mut out = windows.to_vec();
    out.push(PauseWindow { from, until });
    Ok(out)
}

/// `windows` after the person resumes at `now`: a running pause ends there, one that has not
/// started is removed, and with nothing live nothing changes.
pub fn resume_pause(windows: &[PauseWindow], now: i64) -> Vec<PauseWindow> {
    let Some(live) = live_pause(windows, now) else {
        return windows.to_vec();
    };
    if live.from >= now {
        // Kotlin's `windows - live` removes the first equal window.
        let mut out = windows.to_vec();
        if let Some(i) = out.iter().position(|w| *w == live) {
            out.remove(i);
        }
        out
    } else {
        windows
            .iter()
            .map(|w| {
                if *w == live {
                    PauseWindow {
                        from: w.from,
                        until: Some(now),
                    }
                } else {
                    *w
                }
            })
            .collect()
    }
}

/// The phase at `now`: Stopped when inactive, whatever its pauses say; else Paused inside a live window.
pub fn reminder_phase(reminder: &Reminder, now: i64) -> ReminderPhase {
    if !reminder.active {
        return ReminderPhase::Stopped;
    }
    live_pause(&reminder.pauses, now).map_or(ReminderPhase::Active, ReminderPhase::Paused)
}

/// Only a scheduled medication has moments a pause could hold back.
pub fn can_pause(reminder: &Reminder) -> bool {
    reminder.active
        && reminder.kind == ReminderKind::Medication
        && !matches!(reminder.schedule, ScheduleTimes::AsNeeded { .. })
}

/// What a Reminder offers in `phase`: Pause while Active (if it can), Resume while Paused, then Stop unless Stopped.
pub fn reminder_actions(reminder: &Reminder, phase: ReminderPhase) -> Vec<ReminderAction> {
    let mut actions = Vec::new();
    match phase {
        ReminderPhase::Active if can_pause(reminder) => actions.push(ReminderAction::Pause),
        ReminderPhase::Paused(_) => actions.push(ReminderAction::Resume),
        _ => {}
    }
    if phase != ReminderPhase::Stopped {
        actions.push(ReminderAction::Stop);
    }
    actions
}

/// Why a move is refused. One case per sentence the person can be shown.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MoveRefusal {
    NotATask,
    NotAfter,
    InThePast,
    TooLate,
    AlreadyAnswered,
    InProgress,
    InAPause,
}

impl MoveRefusal {
    pub fn wire(self) -> &'static str {
        match self {
            Self::NotATask => "notATask",
            Self::NotAfter => "notAfter",
            Self::InThePast => "inThePast",
            Self::TooLate => "tooLate",
            Self::AlreadyAnswered => "alreadyAnswered",
            Self::InProgress => "inProgress",
            Self::InAPause => "inAPause",
        }
    }
}

/// Whether `reminder` may move the occurrence at `from` to `to` as of `now`, `instances` being
/// those already minted at that moment. The source's blockers come before the target's.
pub fn move_refusal(
    reminder: &Reminder,
    from: i64,
    to: i64,
    now: i64,
    instances: &[ReminderInstance],
) -> Option<MoveRefusal> {
    if reminder.kind != ReminderKind::Task {
        return Some(MoveRefusal::NotATask);
    }
    if now - from > GRACE_PERIOD_MS {
        return Some(MoveRefusal::TooLate);
    }
    let answered = |s: InstanceStatus| {
        matches!(
            s,
            InstanceStatus::Completed | InstanceStatus::Skipped | InstanceStatus::Missed
        )
    };
    if instances.iter().any(|i| answered(i.status)) {
        return Some(MoveRefusal::AlreadyAnswered);
    }
    // What is left is pending or snoozed: a row whose moment the server already holds.
    if !instances.is_empty() {
        return Some(MoveRefusal::InProgress);
    }
    if to <= from {
        return Some(MoveRefusal::NotAfter);
    }
    if to <= now {
        return Some(MoveRefusal::InThePast);
    }
    if is_paused_at(&reminder.pauses, from) || is_paused_at(&reminder.pauses, to) {
        return Some(MoveRefusal::InAPause);
    }
    None
}

/// `overrides` with `added`, replacing any move of the same moment, ordered by source.
pub fn with_override(
    overrides: &[OccurrenceOverride],
    added: OccurrenceOverride,
) -> Vec<OccurrenceOverride> {
    let mut out: Vec<OccurrenceOverride> = overrides
        .iter()
        .filter(|o| o.from != added.from)
        .cloned()
        .collect();
    out.push(added);
    out.sort_by_key(|o| o.from);
    out
}

/// `overrides` without the move of `from`, if there is one.
pub fn without_override(overrides: &[OccurrenceOverride], from: i64) -> Vec<OccurrenceOverride> {
    overrides
        .iter()
        .filter(|o| o.from != from)
        .cloned()
        .collect()
}
