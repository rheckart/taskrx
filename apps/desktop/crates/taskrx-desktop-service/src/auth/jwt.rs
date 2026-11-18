//! The two claims the desktop reads from its own ID token: whose it is and when it expires.
//! The signature is Convex's to check, not ours: this only decides when to refresh.

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD as B64;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Claims {
    /// The Clerk user id, which `getCurrentUserId` returns on the server.
    pub sub: String,
    /// Unix ms.
    pub expires_at: i64,
}

pub fn claims(token: &str) -> Option<Claims> {
    let mut parts = token.split('.');
    let (_, body, _) = (parts.next()?, parts.next()?, parts.next()?);
    if parts.next().is_some() {
        return None;
    }
    let bytes = B64.decode(body.trim_end_matches('=')).ok()?;
    let body: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
    Some(Claims {
        sub: body.get("sub")?.as_str()?.to_string(),
        expires_at: (body.get("exp")?.as_f64()? * 1000.0) as i64,
    })
}

#[cfg(test)]
pub(crate) fn unsigned(sub: &str, exp_seconds: i64) -> String {
    let header = B64.encode(br#"{"alg":"none"}"#);
    let body = B64.encode(format!(r#"{{"sub":"{sub}","exp":{exp_seconds}}}"#));
    format!("{header}.{body}.sig")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_subject_and_expiry() {
        assert_eq!(
            claims(&unsigned("user_1", 1_800_000_000)),
            Some(Claims {
                sub: "user_1".into(),
                expires_at: 1_800_000_000_000
            })
        );
    }

    #[test]
    fn refuses_what_is_not_a_jwt() {
        assert_eq!(claims("opaque"), None);
        assert_eq!(claims("a.b"), None);
        assert_eq!(claims("a.b.c.d"), None);
        assert_eq!(claims("a.!!!.c"), None);
    }
}
