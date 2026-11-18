//! The token endpoint: the code exchange that ends a sign-in, and the refresh grant that keeps it.

use std::fmt;

use serde_json::Value;

use super::jwt;
use crate::config::Config;

/// What one grant returned. Both tokens are secrets: `Debug` never prints them.
#[derive(Clone, PartialEq, Eq)]
pub struct Tokens {
    /// Sent to Convex. Its `aud` is the OAuth client id.
    pub id_token: String,
    /// Single use: Clerk rotates it on every refresh, so the newest one is the only one kept.
    pub refresh_token: String,
    pub user_id: String,
    /// When the ID token stops being accepted, Unix ms.
    pub expires_at: i64,
}

impl fmt::Debug for Tokens {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("Tokens")
            .field("user_id", &self.user_id)
            .field("expires_at", &self.expires_at)
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GrantError {
    /// Clerk said no: the refresh token was revoked, expired or already used. Signed out.
    Refused(String),
    /// No answer, or one that says nothing about the grant: try again later.
    Unreachable(String),
}

impl fmt::Display for GrantError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            GrantError::Refused(why) => write!(f, "sign-in refused: {why}"),
            GrantError::Unreachable(why) => write!(f, "couldn't reach sign-in: {why}"),
        }
    }
}

/// Reads a token response. `status` is the HTTP status it came with.
pub fn tokens_from(status: u16, body: &Value) -> Result<Tokens, GrantError> {
    if let Some(error) = body.get("error").and_then(Value::as_str) {
        // 400 and 401 are the endpoint's answers about this grant; anything else is the service.
        return Err(if status == 400 || status == 401 {
            GrantError::Refused(error.to_string())
        } else {
            GrantError::Unreachable(format!("{status} {error}"))
        });
    }
    if !(200..300).contains(&status) {
        return Err(GrantError::Unreachable(format!("status {status}")));
    }
    let text = |key: &str| body.get(key).and_then(Value::as_str);
    let (Some(id_token), Some(refresh_token)) = (text("id_token"), text("refresh_token")) else {
        return Err(GrantError::Unreachable(
            "the answer had no ID or refresh token".into(),
        ));
    };
    let claims = jwt::claims(id_token)
        .ok_or_else(|| GrantError::Unreachable("the ID token could not be read".into()))?;
    Ok(Tokens {
        id_token: id_token.to_string(),
        refresh_token: refresh_token.to_string(),
        user_id: claims.sub,
        expires_at: claims.expires_at,
    })
}

async fn grant(
    http: &reqwest::Client,
    config: &Config,
    form: &[(&str, &str)],
) -> Result<Tokens, GrantError> {
    let reply = http
        .post(format!("{}/oauth/token", config.issuer))
        .form(form)
        .send()
        .await
        .map_err(|e| GrantError::Unreachable(e.without_url().to_string()))?;
    let status = reply.status().as_u16();
    let body: Value = reply
        .json()
        .await
        .map_err(|_| GrantError::Unreachable(format!("status {status}, not JSON")))?;
    tokens_from(status, &body)
}

pub async fn exchange_code(
    http: &reqwest::Client,
    config: &Config,
    code: &str,
    redirect: &str,
    verifier: &str,
) -> Result<Tokens, GrantError> {
    grant(
        http,
        config,
        &[
            ("grant_type", "authorization_code"),
            ("code", code),
            ("redirect_uri", redirect),
            ("client_id", &config.client_id),
            ("code_verifier", verifier),
        ],
    )
    .await
}

pub async fn refresh(
    http: &reqwest::Client,
    config: &Config,
    refresh_token: &str,
) -> Result<Tokens, GrantError> {
    grant(
        http,
        config,
        &[
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("client_id", &config.client_id),
        ],
    )
    .await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::auth::jwt::unsigned;
    use serde_json::json;

    #[test]
    fn a_grant_gives_both_tokens_and_whose_they_are() {
        let id = unsigned("user_1", 1_800_000_000);
        let t = tokens_from(
            200,
            &json!({ "id_token": id, "refresh_token": "r2", "access_token": "a" }),
        )
        .unwrap();
        assert_eq!(t.user_id, "user_1");
        assert_eq!(t.expires_at, 1_800_000_000_000);
        assert_eq!(t.refresh_token, "r2");
    }

    #[test]
    fn a_refused_grant_signs_out_and_a_failing_service_does_not() {
        assert_eq!(
            tokens_from(400, &json!({ "error": "invalid_grant" })),
            Err(GrantError::Refused("invalid_grant".into()))
        );
        assert!(matches!(
            tokens_from(503, &json!({ "error": "temporarily_unavailable" })),
            Err(GrantError::Unreachable(_))
        ));
        assert!(matches!(
            tokens_from(502, &json!({})),
            Err(GrantError::Unreachable(_))
        ));
        assert!(matches!(
            tokens_from(200, &json!({ "access_token": "a" })),
            Err(GrantError::Unreachable(_))
        ));
    }

    #[test]
    fn debug_never_prints_a_token() {
        let t = Tokens {
            id_token: "secret-id".into(),
            refresh_token: "secret-refresh".into(),
            user_id: "user_1".into(),
            expires_at: 1,
        };
        let printed = format!("{t:?}");
        assert!(!printed.contains("secret"));
        assert!(printed.contains("user_1"));
    }
}
