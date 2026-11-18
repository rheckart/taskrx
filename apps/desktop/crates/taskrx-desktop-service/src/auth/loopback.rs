//! The sign-in round trip: open the browser on Clerk, wait for it to come back to a port the OS
//! picked on 127.0.0.1, and exchange the code.

use std::fmt;
use std::time::Duration;

use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

use super::pkce::{self, Callback, Pkce};
use super::session::{Session, SessionError};
use super::tokens::{self, GrantError};
use crate::config::Config;

/// How long the browser has to come back.
pub const SIGN_IN_WAIT: Duration = Duration::from_secs(5 * 60);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SignInError {
    Browser(String),
    TimedOut,
    Refused(String),
    Grant(GrantError),
    Session(SessionError),
    Io(String),
}

impl fmt::Display for SignInError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SignInError::Browser(why) => write!(f, "couldn't open the browser: {why}"),
            SignInError::TimedOut => write!(f, "the browser didn't come back in time"),
            SignInError::Refused(why) => write!(f, "sign-in was refused ({why})"),
            SignInError::Grant(e) => write!(f, "{e}"),
            SignInError::Session(e) => write!(f, "{e}"),
            SignInError::Io(why) => write!(f, "{why}"),
        }
    }
}

const PAGE_DONE: &str = "Signed in to TaskRx. You can close this tab.";
const PAGE_FAILED: &str = "TaskRx could not sign in. Go back to the app and try again.";

async fn answer(stream: &mut TcpStream, status: &str, body: &str) {
    let reply = format!(
        "HTTP/1.1 {status}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    let _ = stream.write_all(reply.as_bytes()).await;
    let _ = stream.shutdown().await;
}

/// Waits for the one request that carries this attempt's state, answering every other with 404.
async fn wait_for_code(listener: &TcpListener, state: &str) -> Result<String, SignInError> {
    loop {
        let (mut stream, _) = listener
            .accept()
            .await
            .map_err(|e| SignInError::Io(e.to_string()))?;
        let mut line = String::new();
        let read = BufReader::new(&mut stream).read_line(&mut line).await;
        if read.is_err() {
            continue;
        }
        let callback = pkce::request_target(line.trim_end())
            .map_or(Callback::NotTheCallback, |t| pkce::read_callback(t, state));
        match callback {
            Callback::Code(code) => {
                answer(&mut stream, "200 OK", PAGE_DONE).await;
                return Ok(code);
            }
            Callback::Refused(why) => {
                answer(&mut stream, "200 OK", PAGE_FAILED).await;
                return Err(SignInError::Refused(why));
            }
            Callback::WrongState | Callback::NotTheCallback => {
                answer(&mut stream, "404 Not Found", "").await;
            }
        }
    }
}

/// One sign-in, start to finish. `open` shows the person the authorize URL (`xdg-open`).
pub async fn sign_in(
    session: &Session,
    config: &Config,
    http: &reqwest::Client,
    open: impl FnOnce(&str) -> std::io::Result<()>,
    wait: Duration,
) -> Result<(), SignInError> {
    let listener = TcpListener::bind(("127.0.0.1", 0))
        .await
        .map_err(|e| SignInError::Io(e.to_string()))?;
    let port = listener
        .local_addr()
        .map_err(|e| SignInError::Io(e.to_string()))?
        .port();
    let redirect = pkce::redirect_uri(port);
    let attempt = Pkce::generate();
    open(&pkce::authorize_url(config, &redirect, &attempt))
        .map_err(|e| SignInError::Browser(e.to_string()))?;
    let code = tokio::time::timeout(wait, wait_for_code(&listener, &attempt.state))
        .await
        .map_err(|_| SignInError::TimedOut)??;
    let tokens = tokens::exchange_code(http, config, &code, &redirect, &attempt.verifier)
        .await
        .map_err(SignInError::Grant)?;
    session.complete(tokens).await.map_err(SignInError::Session)
}

/// Opens a URL in the person's browser.
pub fn xdg_open(url: &str) -> std::io::Result<()> {
    std::process::Command::new("xdg-open")
        .arg(url)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn get(port: u16, target: &str) -> String {
        let mut stream = TcpStream::connect(("127.0.0.1", port)).await.unwrap();
        stream
            .write_all(format!("GET {target} HTTP/1.1\r\nHost: x\r\n\r\n").as_bytes())
            .await
            .unwrap();
        let mut reply = String::new();
        let mut reader = BufReader::new(stream);
        reader.read_line(&mut reply).await.unwrap();
        reply
    }

    #[tokio::test]
    async fn the_port_takes_only_this_attempts_callback() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let waiting = tokio::spawn(async move { wait_for_code(&listener, "s1").await });
        assert!(get(port, "/favicon.ico").await.contains("404"));
        assert!(
            get(port, "/callback?code=x&state=forged")
                .await
                .contains("404")
        );
        assert!(
            get(port, "/callback?code=good&state=s1")
                .await
                .contains("200")
        );
        assert_eq!(waiting.await.unwrap(), Ok("good".to_string()));
    }

    #[tokio::test]
    async fn a_cancelled_sign_in_comes_back_refused() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let waiting = tokio::spawn(async move { wait_for_code(&listener, "s1").await });
        get(port, "/callback?error=access_denied&state=s1").await;
        assert_eq!(
            waiting.await.unwrap(),
            Err(SignInError::Refused("access_denied".into()))
        );
    }
}
