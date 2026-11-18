//! Where the desktop signs in and what it talks to. All of it is public: the OAuth application
//! is a public client (PKCE, no secret), and the deployment URL is the one the phone uses.

/// The self-hosted Convex deployment (`infra/convex`).
pub const DEPLOYMENT_URL: &str = "https://taskrx.caballus.tech";
/// The Clerk instance, which issues the ID token Convex checks.
pub const ISSUER: &str = "https://funky-flounder-34.clerk.accounts.dev";
/// The Clerk OAuth application "TaskRx Desktop". Convex's second `auth.config.ts` entry
/// (`CLERK_DESKTOP_CLIENT_ID`) checks the ID token's `aud` against it.
pub const CLIENT_ID: &str = "pZBo7hPNxnn4yT04";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Config {
    pub deployment_url: String,
    pub issuer: String,
    pub client_id: String,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            deployment_url: DEPLOYMENT_URL.to_string(),
            issuer: ISSUER.to_string(),
            client_id: CLIENT_ID.to_string(),
        }
    }
}

impl Config {
    /// The defaults, with `TASKRX_DEPLOYMENT_URL` pointing at another deployment (a local
    /// backend in development).
    pub fn from_env() -> Config {
        let mut config = Config::default();
        if let Ok(url) = std::env::var("TASKRX_DEPLOYMENT_URL")
            && !url.trim().is_empty()
        {
            config.deployment_url = url.trim().to_string();
        }
        config
    }
}
