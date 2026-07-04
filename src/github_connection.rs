//! Connected GitHub account (for git push), connected via the dashboard.
//!
//! This is the persisted side of the "Connect GitHub" flow. A single GitHub
//! OAuth account is stored globally for the backend process — mirroring how
//! [`crate::ai_providers::AIProviderStore`] persists provider credentials — and
//! at mission-prep time its token and commit identity are materialized into
//! each workspace as git credentials by [`crate::workspace::git_credentials`].
//!
//! The HTTP side of the flow (authorize / callback / status / disconnect) lives
//! in [`crate::api::github_integration`]. The token is a single secret, so the
//! backing file is written `0600` via [`crate::util::write_file_0600`].

use std::path::{Path, PathBuf};
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use tokio::sync::RwLock;

use crate::util::write_file_0600;

/// A connected GitHub account. Persisted as a single JSON object.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GithubConnection {
    /// OAuth access token (`gho_…` for OAuth Apps). Presented to git over HTTPS.
    pub access_token: String,
    /// Token type from the OAuth response (typically `bearer`).
    #[serde(default)]
    pub token_type: String,
    /// Scopes granted, as returned by GitHub (comma-separated, e.g.
    /// `repo,read:user,user:email`).
    #[serde(default)]
    pub scope: String,
    /// GitHub login (username).
    pub login: String,
    /// Numeric GitHub user id, used to build the `noreply` commit email.
    #[serde(default)]
    pub user_id: u64,
    /// Display name from the GitHub profile, if any.
    #[serde(default)]
    pub name: Option<String>,
    /// Commit email: the account's primary verified email when available,
    /// otherwise the GitHub `noreply` form. Always set so `git commit` works.
    #[serde(default)]
    pub email: Option<String>,
    /// When the account was connected (unix seconds).
    #[serde(default)]
    pub connected_at: i64,
}

impl GithubConnection {
    /// Granted scopes as a vector, splitting on commas or whitespace.
    pub fn scopes(&self) -> Vec<String> {
        self.scope
            .split([',', ' '])
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .collect()
    }

    /// The commit author name to use: the profile name, falling back to login.
    pub fn commit_name(&self) -> String {
        match self.name.as_deref().map(str::trim) {
            Some(n) if !n.is_empty() => n.to_string(),
            _ => self.login.clone(),
        }
    }

    /// The commit author email to use: the stored email, falling back to the
    /// GitHub `noreply` form so `git commit` always has a valid identity.
    pub fn commit_email(&self) -> String {
        match self.email.as_deref().map(str::trim) {
            Some(e) if !e.is_empty() => e.to_string(),
            _ => format!("{}+{}@users.noreply.github.com", self.user_id, self.login),
        }
    }
}

/// In-memory store for the single connected GitHub account, backed by a JSON
/// file. Reads are served from memory; mutations persist atomically to disk.
#[derive(Debug, Clone)]
pub struct GithubConnectionStore {
    connection: Arc<RwLock<Option<GithubConnection>>>,
    storage_path: PathBuf,
}

impl GithubConnectionStore {
    pub async fn new(storage_path: PathBuf) -> Self {
        let store = Self {
            connection: Arc::new(RwLock::new(None)),
            storage_path,
        };
        if let Ok(Some(loaded)) = store.load_from_disk() {
            *store.connection.write().await = Some(loaded);
        }
        store
    }

    fn load_from_disk(&self) -> Result<Option<GithubConnection>, std::io::Error> {
        Ok(Self::read_from_path(&self.storage_path))
    }

    /// Read a stored connection directly from a file, ignoring any in-memory
    /// state. Used by the workspace credential injector, which runs outside the
    /// API process and only knows candidate file paths. Returns `None` when the
    /// file is missing, empty, or unparseable.
    pub fn read_from_path(path: &Path) -> Option<GithubConnection> {
        let contents = std::fs::read_to_string(path).ok()?;
        if contents.trim().is_empty() {
            return None;
        }
        serde_json::from_str(&contents).ok()
    }

    pub async fn get(&self) -> Option<GithubConnection> {
        self.connection.read().await.clone()
    }

    pub async fn set(&self, conn: GithubConnection) -> Result<(), std::io::Error> {
        {
            let mut guard = self.connection.write().await;
            *guard = Some(conn);
        }
        self.save_to_disk().await
    }

    /// Disconnect: drop the in-memory connection and remove the on-disk file so
    /// no stale token is left behind.
    pub async fn clear(&self) -> Result<(), std::io::Error> {
        {
            let mut guard = self.connection.write().await;
            *guard = None;
        }
        match std::fs::remove_file(&self.storage_path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(e) => Err(e),
        }
    }

    async fn save_to_disk(&self) -> Result<(), std::io::Error> {
        let guard = self.connection.read().await;
        let Some(conn) = guard.as_ref() else {
            return Ok(());
        };
        if let Some(parent) = self.storage_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let contents = serde_json::to_string_pretty(conn)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
        // Write-then-rename for crash safety (atomic on POSIX); both the temp
        // and final files are 0600 since they carry the OAuth token.
        let tmp_path = self.storage_path.with_extension("tmp");
        write_file_0600(&tmp_path, contents.as_bytes())?;
        std::fs::rename(&tmp_path, &self.storage_path)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(login: &str, name: Option<&str>, email: Option<&str>) -> GithubConnection {
        GithubConnection {
            access_token: "gho_tok".into(),
            token_type: "bearer".into(),
            scope: "repo,read:user,user:email".into(),
            login: login.into(),
            user_id: 42,
            name: name.map(str::to_string),
            email: email.map(str::to_string),
            connected_at: 0,
        }
    }

    #[test]
    fn scopes_split_on_commas() {
        let c = sample("octocat", None, None);
        assert_eq!(c.scopes(), vec!["repo", "read:user", "user:email"]);
    }

    #[test]
    fn commit_identity_prefers_profile_then_falls_back() {
        let full = sample("octocat", Some("Mona Lisa"), Some("mona@example.com"));
        assert_eq!(full.commit_name(), "Mona Lisa");
        assert_eq!(full.commit_email(), "mona@example.com");

        // No name/email → login + GitHub noreply form.
        let bare = sample("octocat", None, None);
        assert_eq!(bare.commit_name(), "octocat");
        assert_eq!(bare.commit_email(), "42+octocat@users.noreply.github.com");

        // Blank values are treated as missing.
        let blank = sample("octocat", Some("  "), Some(""));
        assert_eq!(blank.commit_name(), "octocat");
        assert_eq!(blank.commit_email(), "42+octocat@users.noreply.github.com");
    }

    #[tokio::test]
    async fn set_get_clear_roundtrip() {
        let dir = std::env::temp_dir().join(format!("ghconn-test-{}", std::process::id()));
        let path = dir.join("github_connection.json");
        let _ = std::fs::remove_dir_all(&dir);

        let store = GithubConnectionStore::new(path.clone()).await;
        assert!(store.get().await.is_none());

        store
            .set(sample("octocat", Some("Mona"), None))
            .await
            .unwrap();
        assert!(path.exists());
        let read_back = GithubConnectionStore::read_from_path(&path).unwrap();
        assert_eq!(read_back.login, "octocat");

        // A fresh store loads the persisted connection from disk.
        let reopened = GithubConnectionStore::new(path.clone()).await;
        assert_eq!(reopened.get().await.unwrap().login, "octocat");

        store.clear().await.unwrap();
        assert!(store.get().await.is_none());
        assert!(!path.exists());

        let _ = std::fs::remove_dir_all(&dir);
    }
}
