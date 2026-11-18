//! The bridge between the window and the service: the session, the connection and a runtime for
//! sign-in. What they report reaches the window through `AppHandle::update`, which runs on the
//! UI thread before the next frame.

use std::sync::{Arc, Mutex};

use rune::AppHandle;
use taskrx_desktop_core::sync::push::PushCall;
use taskrx_desktop_service::auth::loopback::{self, SIGN_IN_WAIT};
use taskrx_desktop_service::auth::{KeyringStore, Session};
use taskrx_desktop_service::config::Config;
use taskrx_desktop_service::connection::{self, Emit, Link};

use crate::ui::{Phase, Ui};

pub struct Host {
    config: Config,
    http: reqwest::Client,
    session: Arc<Session>,
    runtime: tokio::runtime::Runtime,
    link: Mutex<Option<Link>>,
    signing_in: Mutex<Option<tokio::task::AbortHandle>>,
    handle: AppHandle<Ui>,
}

impl Host {
    /// Opens the keyring and says where the person stands: signed in from a saved session, or
    /// at the sign-in screen.
    pub fn start(handle: AppHandle<Ui>) {
        let store = match KeyringStore::open() {
            Ok(store) => store,
            Err(e) => {
                handle.update(move |s: &mut Ui, _| {
                    s.phase = Phase::SignedOut {
                        problem: Some(format!("No keyring to keep the sign-in in ({e})")),
                    }
                });
                return;
            }
        };
        let config = Config::from_env();
        let http = reqwest::Client::new();
        let session = Arc::new(Session::new(config.clone(), http.clone(), store));
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .expect("a tokio runtime");
        let saved = runtime.block_on(session.has_saved());
        let host = Arc::new(Host {
            config,
            http,
            session,
            runtime,
            link: Mutex::new(None),
            signing_in: Mutex::new(None),
            handle: handle.clone(),
        });
        let phase = match saved {
            Ok(true) => {
                host.connect();
                Phase::SignedIn
            }
            Ok(false) => Phase::SignedOut { problem: None },
            Err(e) => Phase::SignedOut {
                problem: Some(e.to_string()),
            },
        };
        handle.update(move |s: &mut Ui, _| {
            s.host = Some(host);
            s.phase = phase;
        });
    }

    pub fn connect(&self) {
        let handle = self.handle.clone();
        let emit: Emit = Arc::new(move |event| {
            handle.update(move |s: &mut Ui, _| s.on_event(event));
        });
        let link = connection::start(self.config.clone(), Arc::clone(&self.session), emit);
        *self.link.lock().expect("link lock") = Some(link);
    }

    pub fn disconnect(&self) {
        self.link.lock().expect("link lock").take();
    }

    pub fn user_id(&self) -> Option<String> {
        self.session.user_id()
    }

    pub fn write(&self, call: PushCall) -> Option<u64> {
        self.link
            .lock()
            .expect("link lock")
            .as_ref()
            .map(|l| l.write(call))
    }

    pub fn cancel_sign_in(&self) {
        if let Some(task) = self.signing_in.lock().expect("sign-in lock").take() {
            task.abort();
        }
    }

    pub fn sign_in(self: &Arc<Self>) {
        self.cancel_sign_in();
        let host = Arc::clone(self);
        let task = self.runtime.spawn(async move {
            let result = loopback::sign_in(
                &host.session,
                &host.config,
                &host.http,
                loopback::xdg_open,
                SIGN_IN_WAIT,
            )
            .await;
            if result.is_ok() {
                host.connect();
            }
            host.handle.update(move |s: &mut Ui, _| match result {
                Ok(()) => s.phase = Phase::SignedIn,
                Err(e) => {
                    s.phase = Phase::SignedOut {
                        problem: Some(e.to_string()),
                    }
                }
            });
        });
        *self.signing_in.lock().expect("sign-in lock") = Some(task.abort_handle());
    }

    pub fn sign_out(self: &Arc<Self>) {
        self.disconnect();
        let host = Arc::clone(self);
        self.runtime.spawn(async move {
            let problem = host.session.sign_out().await.err().map(|e| e.to_string());
            host.handle
                .update(move |s: &mut Ui, _| s.signed_out(problem));
        });
    }
}
