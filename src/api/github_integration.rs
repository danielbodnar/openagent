//! "Connect GitHub" integration: OAuth **device flow** + status + disconnect.
//!
//! Mirrors the shape of the AI-providers OAuth flow, but for a *git-push*
//! GitHub account rather than an LLM backend. The granted token is stored in
//! [`crate::github_connection::GithubConnectionStore`] and injected into each
//! mission workspace as git credentials (see
//! [`crate::workspace::git_credentials`]).
//!
//! Unlike the browser "Sign in with GitHub" login, this uses the GitHub
//! **device flow** — the same flow `gh auth login` uses. That means:
//!
//! - a *public* OAuth App client_id ships in the binary
//!   ([`DEFAULT_DEVICE_FLOW_CLIENT_ID`]); no per-deployment env vars and no
//!   `client_secret` are required (device flow never uses a secret),
//! - there is **no redirect/callback URL** to register (a deal-breaker for the
//!   web flow, since every self-hosted deployment has a different public URL).
//!
//! Self-hosters may still point the integration at their own OAuth App by
//! setting `GITHUB_OAUTH_CLIENT_ID` (the app must have "Device Flow" enabled).
//!
//! Endpoints (all auth-protected like the other dashboard APIs):
//!
//! - `POST /api/integrations/github/authorize` — start: asks GitHub for a
//!   device code and returns the one-time `user_code` + `verification_uri` the
//!   dashboard shows the user; a background task then polls GitHub for the
//!   token and stores it.
//! - `GET  /api/integrations/github/status`    — connection status (and any
//!   in-flight device code) for the card; the dashboard polls this to detect
//!   completion.
//! - `DELETE /api/integrations/github`         — disconnect.

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::{extract::State, http::StatusCode, Json};
use serde::{Deserialize, Serialize};

use super::routes::AppState;
use crate::github_connection::GithubConnection;
use crate::workspace::git_credentials::GitCredentialConfig;

/// OAuth scopes requested. `repo` grants push to private + public repos (the
/// broad classic-OAuth grant the connect card warns about); `read:org` is the
/// scope `gh` flags as required for its own self-check + org commands;
/// `workflow` lets agents push changes to `.github/workflows`; `read:user` and
/// `user:email` are used to resolve the commit identity. This set mirrors what
/// `gh auth login` requests, so `gh auth status` reports no missing scopes.
const GITHUB_INTEGRATION_SCOPES: &str = "repo read:org workflow read:user user:email";

/// Bundled OAuth App client_id used for the device flow. Like the GitHub CLI's
/// hardcoded client_id, this is a **public** value (device flow never uses a
/// secret), so it ships in the binary and needs no per-deployment config.
/// Override it by setting `GITHUB_OAUTH_CLIENT_ID` to your own app's id.
const DEFAULT_DEVICE_FLOW_CLIENT_ID: &str = "Ov23liPgfYHFzLg8xZLn";

/// Key for the single in-flight device authorization in
/// [`AppState::pending_github_integration`] (the integration is single-account).
const PENDING_KEY: &str = "current";

/// An in-flight device authorization, surfaced by [`status`] so the dashboard
/// can (re)display the one-time code even if the verification tab was closed.
#[derive(Debug, Clone)]
pub struct PendingGithubIntegration {
    pub user_code: String,
    pub verification_uri: String,
    pub expires_at: i64,
}

fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// The OAuth App client_id for the device flow: an operator override via
/// `GITHUB_OAUTH_CLIENT_ID` when set, else the bundled default. Never empty.
fn client_id(state: &AppState) -> String {
    state
        .config
        .auth
        .github_oauth_client_id
        .as_deref()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .unwrap_or(DEFAULT_DEVICE_FLOW_CLIENT_ID)
        .to_string()
}

async fn clear_pending(state: &AppState) {
    state
        .pending_github_integration
        .write()
        .await
        .remove(PENDING_KEY);
}

// --- authorize (device flow) -----------------------------------------------

#[derive(Debug, Deserialize)]
struct DeviceCodeResponse {
    device_code: String,
    user_code: String,
    verification_uri: String,
    #[serde(default)]
    verification_uri_complete: Option<String>,
    #[serde(default)]
    expires_in: i64,
    #[serde(default)]
    interval: i64,
}

#[derive(Serialize)]
pub struct AuthorizeResponse {
    /// One-time code the user types at `verification_uri`.
    pub user_code: String,
    /// Where the user enters the code (`https://github.com/login/device`).
    pub verification_uri: String,
    /// `verification_uri` with the code pre-filled, when GitHub provides it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub verification_uri_complete: Option<String>,
    /// Suggested poll interval (seconds).
    pub interval: i64,
    /// Seconds until the code expires.
    pub expires_in: i64,
}

pub async fn authorize(
    State(state): State<Arc<AppState>>,
) -> Result<Json<AuthorizeResponse>, (StatusCode, String)> {
    let client_id = client_id(&state);

    let resp: DeviceCodeResponse = state
        .http_client
        .post("https://github.com/login/device/code")
        .header("Accept", "application/json")
        .header("User-Agent", "sandboxed-dashboard")
        .form(&[
            ("client_id", client_id.as_str()),
            ("scope", GITHUB_INTEGRATION_SCOPES),
        ])
        .send()
        .await
        .map_err(|e| {
            (
                StatusCode::BAD_GATEWAY,
                format!("device code request failed: {e}"),
            )
        })?
        .json()
        .await
        .map_err(|e| {
            (
                StatusCode::BAD_GATEWAY,
                format!("could not parse device code response: {e}"),
            )
        })?;

    // GitHub mandates honoring `interval`; clamp to a sane floor. Default the
    // expiry to 15 minutes if GitHub omits it.
    let interval = resp.interval.max(5);
    let expires_in = if resp.expires_in > 0 {
        resp.expires_in
    } else {
        900
    };

    {
        let mut pending = state.pending_github_integration.write().await;
        pending.insert(
            PENDING_KEY.to_string(),
            PendingGithubIntegration {
                user_code: resp.user_code.clone(),
                verification_uri: resp.verification_uri.clone(),
                expires_at: now_unix() + expires_in,
            },
        );
    }

    // Poll GitHub for the token in the background; the dashboard polls `status`
    // to learn when the connection lands.
    tokio::spawn(poll_for_token(
        state.clone(),
        client_id,
        resp.device_code,
        interval,
        expires_in,
    ));

    Ok(Json(AuthorizeResponse {
        user_code: resp.user_code,
        verification_uri: resp.verification_uri,
        verification_uri_complete: resp.verification_uri_complete,
        interval,
        expires_in,
    }))
}

// --- background token poll --------------------------------------------------

#[derive(Debug, Deserialize)]
struct DeviceTokenResponse {
    access_token: Option<String>,
    token_type: Option<String>,
    scope: Option<String>,
    error: Option<String>,
}

/// Poll GitHub's token endpoint until the user approves the code, it expires,
/// or they deny it. On success, fetch the account + commit email and persist
/// the connection. Clears the pending entry on every terminal outcome.
async fn poll_for_token(
    state: Arc<AppState>,
    client_id: String,
    device_code: String,
    mut interval: i64,
    expires_in: i64,
) {
    let deadline = now_unix() + expires_in;
    loop {
        tokio::time::sleep(Duration::from_secs(interval.max(1) as u64)).await;
        if now_unix() >= deadline {
            tracing::info!("GitHub device flow timed out before approval");
            break;
        }

        let resp: DeviceTokenResponse = match state
            .http_client
            .post("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json")
            .header("User-Agent", "sandboxed-dashboard")
            .form(&[
                ("client_id", client_id.as_str()),
                ("device_code", device_code.as_str()),
                ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ])
            .send()
            .await
        {
            Ok(r) => match r.json().await {
                Ok(parsed) => parsed,
                Err(e) => {
                    tracing::warn!("GitHub device token parse error: {e}");
                    continue;
                }
            },
            Err(e) => {
                tracing::warn!("GitHub device token request error: {e}");
                continue;
            }
        };

        if let Some(access_token) = resp.access_token.filter(|t| !t.is_empty()) {
            let user = match fetch_github_user(&state.http_client, &access_token).await {
                Ok(u) => u,
                Err(e) => {
                    tracing::error!("GitHub device flow: failed to fetch account: {e}");
                    break;
                }
            };
            let email = fetch_primary_email(&state.http_client, &access_token).await;

            let conn = GithubConnection {
                access_token,
                token_type: resp.token_type.unwrap_or_else(|| "bearer".to_string()),
                scope: resp.scope.unwrap_or_default(),
                login: user.login.clone(),
                user_id: user.id,
                name: user.name.clone(),
                email,
                connected_at: now_unix(),
            };

            match state.github_connection.set(conn).await {
                Ok(()) => {
                    tracing::info!(login = %user.login, "GitHub account connected (device flow)")
                }
                Err(e) => tracing::error!("GitHub device flow: failed to save connection: {e}"),
            }
            clear_pending(&state).await;
            return;
        }

        match resp.error.as_deref() {
            // Not approved yet — keep polling at the current cadence.
            Some("authorization_pending") => continue,
            // GitHub asked us to back off.
            Some("slow_down") => {
                interval += 5;
                continue;
            }
            Some("access_denied") => {
                tracing::info!("GitHub device flow denied by user");
                break;
            }
            Some("expired_token") | None => {
                tracing::info!("GitHub device flow code expired");
                break;
            }
            Some(other) => {
                tracing::warn!("GitHub device flow error: {other}");
                break;
            }
        }
    }
    clear_pending(&state).await;
}

#[derive(Debug, Deserialize)]
struct GithubUser {
    login: String,
    id: u64,
    #[serde(default)]
    name: Option<String>,
}

#[derive(Debug, Deserialize)]
struct GithubEmail {
    email: String,
    primary: bool,
    verified: bool,
}

async fn fetch_github_user(client: &reqwest::Client, token: &str) -> Result<GithubUser, String> {
    let resp = client
        .get("https://api.github.com/user")
        .bearer_auth(token)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "sandboxed-dashboard")
        .send()
        .await
        .map_err(|e| e.to_string())?;
    if !resp.status().is_success() {
        return Err(format!("GitHub /user returned {}", resp.status()));
    }
    resp.json().await.map_err(|e| e.to_string())
}

/// Best-effort: the primary verified email, else any verified email. Returns
/// `None` when the `user:email` scope is absent or the call fails — the store
/// then falls back to the GitHub `noreply` form for the commit identity.
async fn fetch_primary_email(client: &reqwest::Client, token: &str) -> Option<String> {
    let resp = client
        .get("https://api.github.com/user/emails")
        .bearer_auth(token)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "sandboxed-dashboard")
        .send()
        .await
        .ok()?;
    if !resp.status().is_success() {
        return None;
    }
    let emails: Vec<GithubEmail> = resp.json().await.ok()?;
    emails
        .iter()
        .find(|e| e.primary && e.verified)
        .or_else(|| emails.iter().find(|e| e.verified))
        .map(|e| e.email.clone())
}

// --- status ----------------------------------------------------------------

#[derive(Serialize)]
pub struct StatusResponse {
    /// Always true now that a device-flow client_id ships in the binary; kept
    /// for API compatibility with the dashboard card.
    configured: bool,
    /// Whether an account is currently connected.
    connected: bool,
    /// Whether a device authorization is in flight (awaiting user approval).
    pending: bool,
    /// The one-time code to enter, while `pending`.
    #[serde(skip_serializing_if = "Option::is_none")]
    user_code: Option<String>,
    /// Where to enter the code, while `pending`.
    #[serde(skip_serializing_if = "Option::is_none")]
    verification_uri: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    login: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    email: Option<String>,
    scopes: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    connected_at: Option<i64>,
}

pub async fn status(State(state): State<Arc<AppState>>) -> Json<StatusResponse> {
    let configured = !client_id(&state).is_empty();

    if let Some(c) = state.github_connection.get().await {
        return Json(StatusResponse {
            configured,
            connected: true,
            pending: false,
            user_code: None,
            verification_uri: None,
            login: Some(c.login.clone()),
            name: c.name.clone(),
            email: c.email.clone(),
            scopes: c.scopes(),
            connected_at: Some(c.connected_at),
        });
    }

    // Not connected — surface any in-flight device authorization (dropping it
    // if it has expired).
    let pending = {
        let now = now_unix();
        let mut store = state.pending_github_integration.write().await;
        store.retain(|_, v| v.expires_at > now);
        store.get(PENDING_KEY).cloned()
    };

    match pending {
        Some(p) => Json(StatusResponse {
            configured,
            connected: false,
            pending: true,
            user_code: Some(p.user_code),
            verification_uri: Some(p.verification_uri),
            login: None,
            name: None,
            email: None,
            scopes: Vec::new(),
            connected_at: None,
        }),
        None => Json(StatusResponse {
            configured,
            connected: false,
            pending: false,
            user_code: None,
            verification_uri: None,
            login: None,
            name: None,
            email: None,
            scopes: Vec::new(),
            connected_at: None,
        }),
    }
}

// --- disconnect ------------------------------------------------------------

pub async fn disconnect(
    State(state): State<Arc<AppState>>,
) -> Result<StatusCode, (StatusCode, String)> {
    clear_pending(&state).await;
    if let Some(conn) = state.github_connection.get().await {
        let creds = GitCredentialConfig::from_connection(&conn);
        let workspaces = state.workspaces.list().await;
        for workspace in workspaces {
            let home = creds
                .scrub_for_workspace(
                    &workspace.path,
                    workspace.workspace_type,
                    &workspace.env_vars,
                )
                .map_err(|e| {
                    (
                        StatusCode::INTERNAL_SERVER_ERROR,
                        format!(
                            "failed to remove GitHub credentials from {}: {e}",
                            workspace.name
                        ),
                    )
                })?;
            tracing::info!(
                workspace = %workspace.name,
                home = %home.display(),
                "Removed GitHub git credentials for workspace"
            );
        }
    }
    state
        .github_connection
        .clear()
        .await
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e.to_string()))?;
    tracing::info!("GitHub account disconnected");
    Ok(StatusCode::NO_CONTENT)
}
