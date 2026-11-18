//! The Convex connection: the four `list` subscriptions in, the answers out.
//!
//! It runs on its own thread with its own tokio runtime, because the window's loop belongs to
//! Rune. What it hears goes out through `emit`; what to do comes in through [`Link`].
//!
//! Auth is a callback, not a fixed token: the client calls it again on every reconnect, and a
//! token the server refuses makes it reconnect (`AuthError` restarts the protocol), so a fresh
//! one is fetched then. Ahead of the token's expiry the callback is set again, which sends the
//! refreshed token on the live connection without dropping the subscriptions.

use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;

use convex::{
    AuthTokenFetcher, AuthenticationToken, ConvexClient, ConvexClientBuilder, FunctionResult,
    Value as ConvexValue, WebSocketState,
};
use futures::StreamExt;
use futures::stream::select_all;
use serde_json::Value;
use taskrx_desktop_core::sync::push::{PushCall, Wire, functions};
use tokio::sync::mpsc;

use crate::auth::session::REFRESH_MARGIN_MS;
use crate::auth::{Session, SessionError};
use crate::config::Config;

/// How long a write waits for the server before it is reported as not sent. The client keeps
/// it queued, so it may still land after this; the subscription then shows it.
pub const WRITE_WAIT: Duration = Duration::from_secs(15);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Table {
    Reminders,
    Instances,
    Notices,
    WatchSources,
}

impl Table {
    pub const ALL: [Table; 4] = [
        Table::Reminders,
        Table::Instances,
        Table::Notices,
        Table::WatchSources,
    ];

    pub fn function(self) -> &'static str {
        match self {
            Table::Reminders => functions::REMINDERS_LIST,
            Table::Instances => functions::INSTANCES_LIST,
            Table::Notices => functions::NOTICES_LIST,
            Table::WatchSources => functions::WATCH_SOURCES_LIST,
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// The socket is up (true) or reconnecting (false).
    Connected(bool),
    /// A list's newest value.
    List(Table, Value),
    /// A list the server refused, with its message ("Not authenticated" before sign-in lands).
    ListFailed(Table, String),
    /// The session ended: no saved sign-in, or Clerk refused it.
    SignedOut,
    /// Sign-in could not be renewed for now.
    AuthUnreachable(String),
    /// A write finished. `ticket` is the one [`Link::write`] returned.
    Wrote {
        ticket: u64,
        result: Result<(), String>,
    },
}

pub type Emit = Arc<dyn Fn(Event) + Send + Sync>;

enum Command {
    Write { ticket: u64, call: PushCall },
    Stop,
}

/// The way in. Dropping it stops the connection.
pub struct Link {
    commands: mpsc::UnboundedSender<Command>,
    next_ticket: std::sync::atomic::AtomicU64,
}

impl Link {
    /// Sends one call; its result comes back as [`Event::Wrote`] with the returned ticket.
    pub fn write(&self, call: PushCall) -> u64 {
        let ticket = self
            .next_ticket
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let _ = self.commands.send(Command::Write { ticket, call });
        ticket
    }
}

impl Drop for Link {
    fn drop(&mut self) {
        let _ = self.commands.send(Command::Stop);
    }
}

pub fn wire_to_convex(wire: &Wire) -> ConvexValue {
    match wire {
        Wire::Null => ConvexValue::Null,
        Wire::Bool(b) => ConvexValue::Boolean(*b),
        Wire::Number(n) => ConvexValue::Float64(*n),
        Wire::Text(s) => ConvexValue::String(s.clone()),
        Wire::List(items) => ConvexValue::Array(items.iter().map(wire_to_convex).collect()),
        Wire::Object(fields) => ConvexValue::Object(
            fields
                .iter()
                .map(|(k, v)| (k.clone(), wire_to_convex(v)))
                .collect(),
        ),
    }
}

fn args(call: &PushCall) -> BTreeMap<String, ConvexValue> {
    call.args
        .iter()
        .map(|(k, v)| (k.clone(), wire_to_convex(v)))
        .collect()
}

fn outcome(result: FunctionResult) -> Result<Value, String> {
    match result {
        FunctionResult::Value(v) => Ok(v.export()),
        FunctionResult::ErrorMessage(m) => Err(m),
        FunctionResult::ConvexError(e) => Err(e.message),
    }
}

/// Starts the connection on its own thread.
pub fn start(config: Config, session: Arc<Session>, emit: Emit) -> Link {
    let (commands, receiver) = mpsc::unbounded_channel();
    std::thread::Builder::new()
        .name("taskrx-convex".into())
        .spawn(move || {
            let runtime = tokio::runtime::Builder::new_multi_thread()
                .worker_threads(2)
                .enable_all()
                .build()
                .expect("a tokio runtime");
            runtime.block_on(run(config, session, emit, receiver));
        })
        .expect("the connection thread");
    Link {
        commands,
        next_ticket: std::sync::atomic::AtomicU64::new(1),
    }
}

fn fetcher(session: Arc<Session>, emit: Emit) -> AuthTokenFetcher {
    Box::new(move |_force_refresh: bool| {
        let (session, emit) = (Arc::clone(&session), Arc::clone(&emit));
        Box::pin(async move {
            match session.id_token().await {
                Ok(token) => Ok(AuthenticationToken::User(token)),
                Err(SessionError::SignedOut) => {
                    emit(Event::SignedOut);
                    Ok(AuthenticationToken::None)
                }
                Err(e) => {
                    emit(Event::AuthUnreachable(e.to_string()));
                    Err(anyhow::anyhow!("{e}"))
                }
            }
        })
    })
}

/// When to send the callback again: a minute inside the refresh margin, so the token it fetches
/// is a refreshed one. Without a token yet, a minute from now.
fn renew_in(session: &Session) -> Duration {
    let now = jiff::Timestamp::now().as_millisecond();
    let due = session
        .expires_at()
        .map_or(now + 60_000, |exp| exp - REFRESH_MARGIN_MS + 60_000);
    Duration::from_millis((due - now).clamp(5_000, 24 * 3_600_000) as u64)
}

async fn connect(config: &Config, states: mpsc::Sender<WebSocketState>) -> ConvexClient {
    let mut wait = Duration::from_secs(1);
    loop {
        match ConvexClientBuilder::new(&config.deployment_url)
            .with_on_state_change(states.clone())
            .build()
            .await
        {
            Ok(client) => return client,
            Err(_) => {
                tokio::time::sleep(wait).await;
                wait = (wait * 2).min(Duration::from_secs(30));
            }
        }
    }
}

async fn run(
    config: Config,
    session: Arc<Session>,
    emit: Emit,
    mut commands: mpsc::UnboundedReceiver<Command>,
) {
    let (state_tx, mut states) = mpsc::channel(16);
    let mut client = connect(&config, state_tx).await;
    client
        .set_auth_callback(Some(fetcher(Arc::clone(&session), Arc::clone(&emit))))
        .await;

    let mut lists = Vec::new();
    for table in Table::ALL {
        match client.subscribe(table.function(), BTreeMap::new()).await {
            Ok(sub) => lists.push(sub.map(move |r| (table, r)).boxed()),
            Err(e) => emit(Event::ListFailed(table, e.to_string())),
        }
    }
    let mut lists = select_all(lists);
    let renewal = tokio::time::sleep(renew_in(&session));
    tokio::pin!(renewal);

    loop {
        tokio::select! {
            Some((table, result)) = lists.next() => {
                emit(match outcome(result) {
                    Ok(value) => Event::List(table, value),
                    Err(message) => Event::ListFailed(table, message),
                });
            }
            Some(state) = states.recv() => {
                emit(Event::Connected(matches!(state, WebSocketState::Connected)));
            }
            command = commands.recv() => match command {
                Some(Command::Write { ticket, call }) => {
                    let (mut client, emit) = (client.clone(), Arc::clone(&emit));
                    tokio::spawn(async move {
                        let sent = tokio::time::timeout(
                            WRITE_WAIT,
                            client.mutation(call.function, args(&call)),
                        )
                        .await;
                        let result = match sent {
                            Err(_) => Err("Timed out waiting for the server".to_string()),
                            Ok(Err(e)) => Err(e.to_string()),
                            Ok(Ok(r)) => outcome(r).map(|_| ()),
                        };
                        emit(Event::Wrote { ticket, result });
                    });
                }
                Some(Command::Stop) | None => return,
            },
            () = &mut renewal => {
                client
                    .set_auth_callback(Some(fetcher(Arc::clone(&session), Arc::clone(&emit))))
                    .await;
                renewal.as_mut().reset(tokio::time::Instant::now() + renew_in(&session));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_number_goes_out_as_a_float64() {
        let wire = Wire::Object(vec![
            ("n".into(), Wire::Number(3.0)),
            ("l".into(), Wire::List(vec![Wire::Number(1.0), Wire::Null])),
        ]);
        let ConvexValue::Object(map) = wire_to_convex(&wire) else {
            panic!("an object")
        };
        assert_eq!(map["n"], ConvexValue::Float64(3.0));
        assert_eq!(
            map["l"],
            ConvexValue::Array(vec![ConvexValue::Float64(1.0), ConvexValue::Null])
        );
    }
}
