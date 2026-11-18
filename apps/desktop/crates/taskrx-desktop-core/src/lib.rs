//! TaskRx's rules for the desktop Companion (ADR 0016, ADR 0017).
//!
//! A port of the phone's `:core` (`apps/android/core`), which stays the reference: every rule
//! here is held to the golden fixtures `:core` writes (`apps/android/core/golden`), and
//! `cargo test` must reproduce each case exactly. Pure: no clock, no I/O.

pub mod domain;
pub mod sync;
pub mod time;
