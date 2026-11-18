//! Signing in (ADR 0017): a browser handoff to the Clerk OAuth application, PKCE S256, redirected
//! to a loopback port the OS picks. Convex is sent the OIDC ID token; the refresh token is the
//! one thing written to disk, in the keyring, and it is replaced on every refresh because Clerk
//! rotates it.

pub mod jwt;
pub mod loopback;
pub mod pkce;
pub mod session;
pub mod store;
pub mod tokens;

pub use session::{Session, SessionError};
pub use store::{KeyringStore, MemoryStore, RefreshStore, StoreError};
pub use tokens::{GrantError, Tokens};
