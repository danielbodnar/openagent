/**
 * "Connect GitHub" integration API.
 *
 * Connects a single GitHub account whose OAuth token is injected into every
 * mission workspace as git credentials, so agents can `git commit` / `git push`
 * with no per-mission setup. Mirrors the AI-providers OAuth flow but is a
 * separate, single-account integration (it is not an inference provider).
 */

import { apiDel, apiGet, apiPost } from "./core";

export interface GithubIntegrationStatus {
  /** Whether a device-flow client_id is available on the backend. Always true
   *  now that one ships in the binary; kept for API compatibility. */
  configured: boolean;
  /** Whether a GitHub account is currently connected. */
  connected: boolean;
  /** Whether a device authorization is in flight (awaiting user approval). */
  pending?: boolean;
  /** One-time code to enter at `verification_uri`, while `pending`. */
  user_code?: string;
  /** Where to enter the code (github.com/login/device), while `pending`. */
  verification_uri?: string;
  /** GitHub login (username) when connected. */
  login?: string;
  /** Display name from the GitHub profile, if any. */
  name?: string;
  /** Commit email derived from the account. */
  email?: string;
  /** Granted OAuth scopes. */
  scopes: string[];
  /** When the account was connected (unix seconds). */
  connected_at?: number;
}

export interface GithubAuthorizeResponse {
  /** One-time code the user types at `verification_uri`. */
  user_code: string;
  /** Where the user enters the code (github.com/login/device). */
  verification_uri: string;
  /** `verification_uri` with the code pre-filled, when GitHub provides it. */
  verification_uri_complete?: string;
  /** Suggested poll interval (seconds). */
  interval: number;
  /** Seconds until the code expires. */
  expires_in: number;
}

/** Current GitHub integration status (connected + account info, or in-flight code). */
export async function getGithubStatus(): Promise<GithubIntegrationStatus> {
  return apiGet("/api/integrations/github/status", "Failed to load GitHub status");
}

/**
 * Begin the device flow (like `gh auth login`): the backend asks GitHub for a
 * device code and starts polling for the token. Returns the one-time code +
 * verification URL to show the user.
 */
export async function authorizeGithub(): Promise<GithubAuthorizeResponse> {
  return apiPost(
    "/api/integrations/github/authorize",
    undefined,
    "Failed to start GitHub authorization",
  );
}

/** Disconnect the GitHub account (removes the stored token). */
export async function disconnectGithub(): Promise<void> {
  await apiDel("/api/integrations/github", "Failed to disconnect GitHub");
}
