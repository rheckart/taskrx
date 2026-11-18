//! The schedule union. Kotlin: `core/domain/ScheduleTimes.kt` (the domain shape only: the
//! desktop reads and writes Convex's shape, `sync::server_schedule`, never the phone's column).

/// When a Reminder comes due. Times are local `"HH:MM"`; instants are Unix milliseconds.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ScheduleTimes {
    /// Fixed local times every day.
    Daily { times: Vec<String> },
    /// Every `hours` hours from `start_at`.
    Interval { hours: i32, start_at: i64 },
    /// Selected weekdays (Sunday = 0 … Saturday = 6) at fixed local times.
    Weekly {
        days_of_week: Vec<i32>,
        times: Vec<String>,
    },
    /// Selected dates (1..31; a date the month lacks lands on its last day) at fixed local times.
    Monthly {
        days_of_month: Vec<i32>,
        times: Vec<String>,
    },
    /// One moment, and then never again.
    Once { at: i64 },
    /// No schedule: the Backlog.
    None,
    /// Taken when needed: no Occurrences, only how soon the next dose is allowed.
    AsNeeded {
        min_gap_minutes: i32,
        max_per_24h: Option<i32>,
    },
}

/// The month dates a schedule may name.
pub const DAYS_OF_MONTH: std::ops::RangeInclusive<i64> = 1..=31;
