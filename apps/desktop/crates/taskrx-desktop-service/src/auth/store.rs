//! Where the refresh token is kept between runs: the Secret Service keyring, never a file
//! (ADR 0016). Every call blocks on D-Bus, so callers run it off the async runtime.

use std::fmt;
use std::sync::{Arc, Mutex, Once};

use keyring_core::{Entry, Error};

const SERVICE: &str = "taskrx-desktop";
const ACCOUNT: &str = "refresh-token";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoreError(pub String);

impl fmt::Display for StoreError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "keyring: {}", self.0)
    }
}

pub trait RefreshStore: Send + Sync {
    fn load(&self) -> Result<Option<String>, StoreError>;
    fn save(&self, token: &str) -> Result<(), StoreError>;
    fn clear(&self) -> Result<(), StoreError>;
}

pub struct KeyringStore {
    entry: Entry,
}

static DEFAULT_STORE: Once = Once::new();

impl KeyringStore {
    pub fn open() -> Result<Arc<KeyringStore>, StoreError> {
        let mut failed = None;
        DEFAULT_STORE.call_once(|| match zbus_secret_service_keyring_store::Store::new() {
            Ok(store) => keyring_core::set_default_store(store),
            Err(e) => failed = Some(e.to_string()),
        });
        if let Some(why) = failed {
            return Err(StoreError(why));
        }
        let entry = Entry::new(SERVICE, ACCOUNT).map_err(|e| StoreError(e.to_string()))?;
        Ok(Arc::new(KeyringStore { entry }))
    }
}

impl RefreshStore for KeyringStore {
    fn load(&self) -> Result<Option<String>, StoreError> {
        match self.entry.get_password() {
            Ok(token) => Ok(Some(token)),
            Err(Error::NoEntry) => Ok(None),
            Err(e) => Err(StoreError(e.to_string())),
        }
    }

    fn save(&self, token: &str) -> Result<(), StoreError> {
        self.entry
            .set_password(token)
            .map_err(|e| StoreError(e.to_string()))
    }

    fn clear(&self) -> Result<(), StoreError> {
        match self.entry.delete_credential() {
            Ok(()) | Err(Error::NoEntry) => Ok(()),
            Err(e) => Err(StoreError(e.to_string())),
        }
    }
}

/// A store that forgets on exit: for tests, and for a desktop with no keyring.
#[derive(Default)]
pub struct MemoryStore(Mutex<Option<String>>);

impl RefreshStore for MemoryStore {
    fn load(&self) -> Result<Option<String>, StoreError> {
        Ok(self.0.lock().expect("store lock").clone())
    }

    fn save(&self, token: &str) -> Result<(), StoreError> {
        *self.0.lock().expect("store lock") = Some(token.to_string());
        Ok(())
    }

    fn clear(&self) -> Result<(), StoreError> {
        *self.0.lock().expect("store lock") = None;
        Ok(())
    }
}
