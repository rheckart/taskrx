//! What each screen draws, as pure functions of the data, the clock and the zone. Every rule they
//! apply is a ported one from `taskrx-desktop-core`; these only choose the inputs, as the phone's
//! view models do.

pub mod backlog;
pub mod catch_up;
pub mod proposals;
pub mod timing;
pub mod today;
