//! The desktop Companion's toolkit-free side (ADR 0016, 0017): signing in through the browser,
//! keeping the refresh token in the keyring, the Convex connection, and the state each screen
//! draws. The Rune crate only draws that state and forwards input.
//!
//! Nothing here writes to disk but the keyring entry: documents live in memory only (ADR 0016).

pub mod auth;
pub mod config;
pub mod connection;
pub mod data;
pub mod screens;
pub mod writes;
