//! The signed-in session: the ID token Convex is sent, refreshed before it runs out.
//!
//! The Rust Convex client does not report a refused token (ADR 0017), so the desktop reads the
//! token's `exp` itself and refreshes ahead of it. Refreshes are serialized: Clerk rotates the
//! refresh token, so two at once would spend the same one and sign the second out.

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use super::store::{RefreshStore, StoreError};
use super::tokens::{self, GrantError, Tokens};
use crate::config::Config;

/// A token with less than this left is refreshed before it is handed out.
pub const REFRESH_MARGIN_MS: i64 = 5 * 60_000;

pub type RefreshFn = Arc<
    dyn Fn(String) -> Pin<Box<dyn Future<Output = Result<Tokens, GrantError>> + Send>>
        + Send
        + Sync,
>;
pub type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SessionError {
    /// No refresh token, or Clerk refused it: the person signs in again.
    SignedOut,
    /// Clerk could not be reached and the token held has run out.
    Unreachable(String),
    Keyring(String),
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SessionError::SignedOut => write!(f, "signed out"),
            SessionError::Unreachable(why) => write!(f, "couldn't reach sign-in: {why}"),
            SessionError::Keyring(why) => write!(f, "keyring: {why}"),
        }
    }
}

pub struct Session {
    store: Arc<dyn RefreshStore>,
    refresh: RefreshFn,
    clock: Clock,
    tokens: tokio::sync::Mutex<Option<Tokens>>,
    /// Whose session this is and when its ID token runs out, readable without waiting.
    held: Mutex<Option<(String, i64)>>,
}

pub fn wall_clock() -> Clock {
    Arc::new(|| jiff::Timestamp::now().as_millisecond())
}

async fn off_runtime<T: Send + 'static>(
    store: &Arc<dyn RefreshStore>,
    call: impl FnOnce(&dyn RefreshStore) -> Result<T, StoreError> + Send + 'static,
) -> Result<T, SessionError> {
    let store = Arc::clone(store);
    tokio::task::spawn_blocking(move || call(store.as_ref()))
        .await
        .map_err(|e| SessionError::Keyring(e.to_string()))?
        .map_err(|e| SessionError::Keyring(e.0))
}

impl Session {
    pub fn new(config: Config, http: reqwest::Client, store: Arc<dyn RefreshStore>) -> Session {
        let refresh: RefreshFn = Arc::new(move |token: String| {
            let (config, http) = (config.clone(), http.clone());
            Box::pin(async move { tokens::refresh(&http, &config, &token).await })
        });
        Session::with(store, refresh, wall_clock())
    }

    pub fn with(store: Arc<dyn RefreshStore>, refresh: RefreshFn, clock: Clock) -> Session {
        Session {
            store,
            refresh,
            clock,
            tokens: tokio::sync::Mutex::new(None),
            held: Mutex::new(None),
        }
    }

    pub fn user_id(&self) -> Option<String> {
        self.held
            .lock()
            .expect("session lock")
            .as_ref()
            .map(|(u, _)| u.clone())
    }

    pub fn expires_at(&self) -> Option<i64> {
        self.held
            .lock()
            .expect("session lock")
            .as_ref()
            .map(|h| h.1)
    }

    fn hold(&self, tokens: Option<&Tokens>) {
        *self.held.lock().expect("session lock") =
            tokens.map(|t| (t.user_id.clone(), t.expires_at));
    }

    /// Whether a refresh token is saved: a start that can skip the sign-in screen.
    pub async fn has_saved(&self) -> Result<bool, SessionError> {
        Ok(off_runtime(&self.store, |s| s.load()).await?.is_some())
    }

    /// The end of a sign-in: the new refresh token is saved before the session uses it.
    pub async fn complete(&self, new: Tokens) -> Result<(), SessionError> {
        let mut tokens = self.tokens.lock().await;
        let refresh = new.refresh_token.clone();
        off_runtime(&self.store, move |s| s.save(&refresh)).await?;
        self.hold(Some(&new));
        *tokens = Some(new);
        Ok(())
    }

    pub async fn sign_out(&self) -> Result<(), SessionError> {
        let mut tokens = self.tokens.lock().await;
        *tokens = None;
        self.hold(None);
        off_runtime(&self.store, |s| s.clear()).await
    }

    /// The ID token to send Convex: the one held while it has [REFRESH_MARGIN_MS] left, else a
    /// refreshed one. A refused refresh clears the keyring and signs out; an unreachable Clerk
    /// keeps a token that has not yet run out.
    pub async fn id_token(&self) -> Result<String, SessionError> {
        let mut tokens = self.tokens.lock().await;
        let now = (self.clock)();
        if let Some(t) = tokens.as_ref()
            && t.expires_at - now > REFRESH_MARGIN_MS
        {
            return Ok(t.id_token.clone());
        }
        let refresh_token = match tokens.as_ref() {
            Some(t) => t.refresh_token.clone(),
            None => off_runtime(&self.store, |s| s.load())
                .await?
                .ok_or(SessionError::SignedOut)?,
        };
        match (self.refresh)(refresh_token).await {
            Ok(new) => {
                // The old refresh token is spent now, so the new one is held even if the
                // keyring refuses it: the session goes on and the next start signs in again.
                let saved = {
                    let refresh = new.refresh_token.clone();
                    off_runtime(&self.store, move |s| s.save(&refresh)).await
                };
                if let Err(e) = saved {
                    eprintln!("taskrx: the refreshed sign-in was not saved ({e})");
                }
                self.hold(Some(&new));
                let id = new.id_token.clone();
                *tokens = Some(new);
                Ok(id)
            }
            Err(GrantError::Refused(_)) => {
                *tokens = None;
                self.hold(None);
                off_runtime(&self.store, |s| s.clear()).await?;
                Err(SessionError::SignedOut)
            }
            Err(GrantError::Unreachable(why)) => match tokens.as_ref() {
                Some(t) if t.expires_at > now => Ok(t.id_token.clone()),
                _ => Err(SessionError::Unreachable(why)),
            },
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::jwt::unsigned;
    use crate::auth::store::MemoryStore;
    use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};

    const NOW: i64 = 1_800_000_000_000;

    fn tokens(n: usize, expires_at: i64) -> Tokens {
        Tokens {
            id_token: unsigned("user_1", expires_at / 1000),
            refresh_token: format!("r{n}"),
            user_id: "user_1".into(),
            expires_at,
        }
    }

    struct Fixture {
        session: Session,
        store: Arc<MemoryStore>,
        clock: Arc<AtomicI64>,
        calls: Arc<AtomicUsize>,
    }

    /// A Clerk whose answer is decided by `answer(the refresh token sent, the call's number)`.
    fn fixture(answer: fn(&str, usize) -> Result<Tokens, GrantError>) -> Fixture {
        let store = Arc::new(MemoryStore::default());
        let clock = Arc::new(AtomicI64::new(NOW));
        let calls = Arc::new(AtomicUsize::new(0));
        let refresh: RefreshFn = {
            let calls = Arc::clone(&calls);
            Arc::new(move |token: String| {
                let n = calls.fetch_add(1, Ordering::SeqCst) + 1;
                let result = answer(&token, n);
                Box::pin(async move { result })
            })
        };
        let session = Session::with(store.clone(), refresh, {
            let clock = Arc::clone(&clock);
            Arc::new(move || clock.load(Ordering::SeqCst))
        });
        Fixture {
            session,
            store,
            clock,
            calls,
        }
    }

    fn rotating(_: &str, n: usize) -> Result<Tokens, GrantError> {
        Ok(tokens(n + 1, NOW + 3_600_000 * n as i64))
    }

    #[tokio::test]
    async fn nothing_saved_is_signed_out() {
        let f = fixture(rotating);
        assert_eq!(f.session.id_token().await, Err(SessionError::SignedOut));
        assert!(!f.session.has_saved().await.unwrap());
        assert_eq!(f.calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn a_saved_token_is_refreshed_and_the_rotated_one_replaces_it() {
        let f = fixture(rotating);
        f.store.save("r1").unwrap();
        let first = f.session.id_token().await.unwrap();
        assert_eq!(f.store.load().unwrap().as_deref(), Some("r2"));
        assert_eq!(f.session.user_id().as_deref(), Some("user_1"));
        // Held while it has time left: no second grant.
        assert_eq!(f.session.id_token().await.unwrap(), first);
        assert_eq!(f.calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn a_token_near_its_end_is_refreshed_first() {
        let f = fixture(rotating);
        f.store.save("r1").unwrap();
        f.session.id_token().await.unwrap();
        f.clock
            .store(NOW + 3_600_000 - REFRESH_MARGIN_MS, Ordering::SeqCst);
        f.session.id_token().await.unwrap();
        assert_eq!(f.calls.load(Ordering::SeqCst), 2);
        assert_eq!(f.store.load().unwrap().as_deref(), Some("r3"));
    }

    #[tokio::test]
    async fn a_refused_refresh_signs_out_and_clears_the_keyring() {
        let f = fixture(|_, _| Err(GrantError::Refused("invalid_grant".into())));
        f.store.save("r1").unwrap();
        assert_eq!(f.session.id_token().await, Err(SessionError::SignedOut));
        assert_eq!(f.store.load().unwrap(), None);
        assert_eq!(f.session.user_id(), None);
    }

    #[tokio::test]
    async fn an_unreachable_clerk_keeps_a_token_still_good() {
        let f = fixture(|_, n| {
            if n == 1 {
                rotating("", 1)
            } else {
                Err(GrantError::Unreachable("offline".into()))
            }
        });
        f.store.save("r1").unwrap();
        let held = f.session.id_token().await.unwrap();
        f.clock.store(NOW + 3_600_000 - 60_000, Ordering::SeqCst);
        assert_eq!(f.session.id_token().await.unwrap(), held);
        f.clock.store(NOW + 3_600_000, Ordering::SeqCst);
        assert!(matches!(
            f.session.id_token().await,
            Err(SessionError::Unreachable(_))
        ));
        // Nothing was refused, so the saved token stays for when Clerk is back.
        assert_eq!(f.store.load().unwrap().as_deref(), Some("r2"));
    }

    #[tokio::test]
    async fn a_sign_in_saves_before_use_and_a_sign_out_forgets() {
        let f = fixture(rotating);
        f.session
            .complete(tokens(7, NOW + 3_600_000))
            .await
            .unwrap();
        assert_eq!(f.store.load().unwrap().as_deref(), Some("r7"));
        assert!(f.session.id_token().await.is_ok());
        assert_eq!(f.calls.load(Ordering::SeqCst), 0);
        f.session.sign_out().await.unwrap();
        assert_eq!(f.store.load().unwrap(), None);
        assert_eq!(f.session.id_token().await, Err(SessionError::SignedOut));
    }
}
