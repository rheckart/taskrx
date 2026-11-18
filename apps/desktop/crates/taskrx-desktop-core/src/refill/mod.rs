//! When a medication's supply runs out, and whether to refill. Kotlin:
//! `core/refill/Projection.kt` (the projection the screens show; the server spends supply).

use jiff::tz::TimeZone;

use crate::domain::{Reminder, ReminderKind};
use crate::schedule::next_occurrences;
use crate::time::local_calendar_days_between;

pub const DEFAULT_REFILL_REMINDER_DAYS: i32 = 7;
/// How far ahead the schedule is walked for the moment the supply runs out.
pub const REFILL_PROJECTION_MAX_OCCURRENCES: i64 = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnprojectableReason {
    NotAMedication,
    Stopped,
    NoSupply,
    NoDoseSize,
}

impl UnprojectableReason {
    pub fn wire(self) -> &'static str {
        match self {
            Self::NotAMedication => "not-a-medication",
            Self::Stopped => "stopped",
            Self::NoSupply => "no-supply",
            Self::NoDoseSize => "no-dose-size",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RefillProjection {
    Unprojectable(UnprojectableReason),
    Outlook {
        occurrences_remaining: i64,
        /// The first moment nothing is left for; `None` past what the schedule can be walked for.
        runs_out_at: Option<i64>,
        days_remaining: Option<i32>,
        warning_window_days: i32,
        needs_refill: bool,
    },
}

pub fn project_refill(medication: &Reminder, now: i64, tz: &TimeZone) -> RefillProjection {
    use RefillProjection::Unprojectable;
    if medication.kind != ReminderKind::Medication {
        return Unprojectable(UnprojectableReason::NotAMedication);
    }
    if !medication.active {
        return Unprojectable(UnprojectableReason::Stopped);
    }
    let Some(pills_remaining) = medication.pills_remaining else {
        return Unprojectable(UnprojectableReason::NoSupply);
    };
    let Some(pills_per_dose) = medication.pills_per_dose.filter(|p| *p > 0.0) else {
        return Unprojectable(UnprojectableReason::NoDoseSize);
    };
    // Thirty pills two at a time is fifteen Occurrences. `as` saturates, as `toLong` does.
    let occurrences_remaining = ((pills_remaining / pills_per_dose).floor() as i64).max(0);
    let warning_window_days = medication
        .refill_reminder_days
        .unwrap_or(DEFAULT_REFILL_REMINDER_DAYS);
    let wanted = occurrences_remaining.min(REFILL_PROJECTION_MAX_OCCURRENCES) + 1;
    let occurrences = next_occurrences(medication, now, wanted, tz).unwrap_or_default();
    let runs_out_at = usize::try_from(occurrences_remaining)
        .ok()
        .and_then(|i| occurrences.get(i))
        .map(|o| o.fire_at);
    let days_remaining = runs_out_at.map(|at| local_calendar_days_between(now, at, tz));
    RefillProjection::Outlook {
        occurrences_remaining,
        runs_out_at,
        days_remaining,
        warning_window_days,
        needs_refill: days_remaining.is_some_and(|d| d <= warning_window_days),
    }
}
