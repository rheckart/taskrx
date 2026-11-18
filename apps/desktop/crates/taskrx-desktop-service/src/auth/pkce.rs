//! The authorization request and the browser's return to the loopback port (RFC 7636, RFC 8252).

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;
use rand::RngCore;
use sha2::{Digest, Sha256};

use crate::config::Config;

pub const SCOPES: &str = "openid profile email offline_access";

/// One sign-in attempt's secrets. The verifier never leaves this process; the browser sees only
/// the challenge and the state.
pub struct Pkce {
    pub verifier: String,
    pub challenge: String,
    pub state: String,
}

impl Pkce {
    pub fn generate() -> Pkce {
        let verifier = random(32);
        Pkce {
            challenge: challenge_for(&verifier),
            verifier,
            state: random(16),
        }
    }
}

fn random(bytes: usize) -> String {
    let mut buffer = vec![0u8; bytes];
    rand::rng().fill_bytes(&mut buffer);
    B64.encode(buffer)
}

/// S256: the unpadded base64url of the verifier's SHA-256.
pub fn challenge_for(verifier: &str) -> String {
    B64.encode(Sha256::digest(verifier.as_bytes()))
}

/// Clerk matches a loopback redirect on any port, so the port is the one the OS gave.
pub fn redirect_uri(port: u16) -> String {
    format!("http://127.0.0.1:{port}/callback")
}

pub fn authorize_url(config: &Config, redirect: &str, pkce: &Pkce) -> String {
    let mut url = url::Url::parse(&format!("{}/oauth/authorize", config.issuer))
        .expect("the issuer is a URL");
    url.query_pairs_mut()
        .append_pair("response_type", "code")
        .append_pair("client_id", &config.client_id)
        .append_pair("redirect_uri", redirect)
        .append_pair("scope", SCOPES)
        .append_pair("state", &pkce.state)
        .append_pair("code_challenge", &pkce.challenge)
        .append_pair("code_challenge_method", "S256");
    url.into()
}

/// What one request to the loopback port turned out to be.
#[derive(Debug, PartialEq, Eq)]
pub enum Callback {
    Code(String),
    /// Clerk sent the person back with an error (they cancelled, or the request was refused).
    Refused(String),
    /// A callback for another attempt, or a forged one.
    WrongState,
    /// Not the callback at all: the browser asking for `/favicon.ico`, say.
    NotTheCallback,
}

/// Reads the target of the browser's `GET` (`/callback?code=…&state=…`).
pub fn read_callback(target: &str, state: &str) -> Callback {
    let Ok(url) = url::Url::parse(&format!("http://127.0.0.1{target}")) else {
        return Callback::NotTheCallback;
    };
    if url.path() != "/callback" {
        return Callback::NotTheCallback;
    }
    let mut code = None;
    let mut returned_state = None;
    let mut error = None;
    for (key, value) in url.query_pairs() {
        match key.as_ref() {
            "code" => code = Some(value.into_owned()),
            "state" => returned_state = Some(value.into_owned()),
            "error" => error = Some(value.into_owned()),
            _ => {}
        }
    }
    if returned_state.as_deref() != Some(state) {
        return Callback::WrongState;
    }
    match (code, error) {
        (_, Some(error)) => Callback::Refused(error),
        (Some(code), None) if !code.is_empty() => Callback::Code(code),
        _ => Callback::Refused("no code".to_string()),
    }
}

/// The request line's target: `GET /callback?… HTTP/1.1` gives `/callback?…`.
pub fn request_target(request_line: &str) -> Option<&str> {
    let mut parts = request_line.split_whitespace();
    match (parts.next(), parts.next()) {
        (Some("GET"), Some(target)) => Some(target),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_challenge_is_rfc_7636s_example() {
        assert_eq!(
            challenge_for("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        );
    }

    #[test]
    fn each_attempt_has_its_own_secrets() {
        let (a, b) = (Pkce::generate(), Pkce::generate());
        assert_ne!(a.verifier, b.verifier);
        assert_ne!(a.state, b.state);
        assert_eq!(a.verifier.len(), 43);
        assert_eq!(a.challenge, challenge_for(&a.verifier));
    }

    #[test]
    fn the_authorize_url_asks_for_s256_and_the_loopback_redirect() {
        let pkce = Pkce {
            verifier: "v".into(),
            challenge: "c".into(),
            state: "s".into(),
        };
        let url = url::Url::parse(&authorize_url(
            &Config::default(),
            &redirect_uri(4321),
            &pkce,
        ))
        .unwrap();
        let pairs: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
        assert_eq!(url.path(), "/oauth/authorize");
        assert_eq!(pairs["redirect_uri"], "http://127.0.0.1:4321/callback");
        assert_eq!(pairs["code_challenge_method"], "S256");
        assert_eq!(pairs["code_challenge"], "c");
        assert_eq!(pairs["state"], "s");
        assert_eq!(pairs["scope"], SCOPES);
        assert!(!pairs.contains_key("code_verifier"));
    }

    #[test]
    fn the_callback_is_read_only_with_its_own_state() {
        assert_eq!(
            read_callback("/callback?code=abc&state=s1", "s1"),
            Callback::Code("abc".into())
        );
        assert_eq!(
            read_callback("/callback?code=abc&state=other", "s1"),
            Callback::WrongState
        );
        assert_eq!(
            read_callback("/callback?code=abc", "s1"),
            Callback::WrongState
        );
        assert_eq!(
            read_callback("/callback?error=access_denied&state=s1", "s1"),
            Callback::Refused("access_denied".into())
        );
        assert_eq!(
            read_callback("/favicon.ico", "s1"),
            Callback::NotTheCallback
        );
    }

    #[test]
    fn only_a_get_has_a_target() {
        assert_eq!(
            request_target("GET /callback?code=1 HTTP/1.1"),
            Some("/callback?code=1")
        );
        assert_eq!(request_target("POST /callback HTTP/1.1"), None);
        assert_eq!(request_target(""), None);
    }
}
