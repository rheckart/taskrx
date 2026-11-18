//! The Missed sweep. Kotlin: `core/sweep/Planner.kt`. The desktop shows its verdicts but
//! never writes them (ADR 0016).

/// How long after its moment an unanswered Occurrence may still be answered on time (ADR 0005).
pub const GRACE_PERIOD_MS: i64 = 2 * 3_600_000;
