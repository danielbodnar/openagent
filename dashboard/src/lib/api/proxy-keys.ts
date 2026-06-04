/**
 * Proxy API Keys - Generate and manage long-lived API keys for external tools
 * to authenticate against the /v1 proxy endpoint.
 */

import { apiGet, apiPost, apiDel } from "./core";

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface ProxyApiKeySummary {
  id: string;
  name: string;
  key_prefix: string;
  created_at: string;
  /** Last successful proxy authentication; null if never used (or the key predates usage tracking). */
  last_used_at: string | null;
}

export interface ProxyApiKeyCleanupResult {
  dry_run: boolean;
  /** Keys whose last activity predates this instant were selected. */
  cutoff: string;
  /** Candidate keys (dry run) or deleted keys. */
  keys: ProxyApiKeySummary[];
}

export interface ProxyApiKeyCreated {
  id: string;
  name: string;
  /** The full API key — only returned once at creation time. */
  key: string;
  created_at: string;
}

// ---------------------------------------------------------------------------
// API
// ---------------------------------------------------------------------------

export async function listProxyApiKeys(): Promise<ProxyApiKeySummary[]> {
  return apiGet("/api/proxy-keys", "Failed to list proxy API keys");
}

export async function createProxyApiKey(name: string): Promise<ProxyApiKeyCreated> {
  return apiPost("/api/proxy-keys", { name }, "Failed to create proxy API key");
}

export async function deleteProxyApiKey(id: string): Promise<void> {
  return apiDel(`/api/proxy-keys/${encodeURIComponent(id)}`, "Failed to delete proxy API key");
}

/**
 * Delete (or, with dryRun, list) keys with no activity for maxAgeDays days.
 */
export async function cleanupProxyApiKeys(
  maxAgeDays: number,
  dryRun: boolean,
): Promise<ProxyApiKeyCleanupResult> {
  return apiPost(
    "/api/proxy-keys/cleanup",
    { max_age_days: maxAgeDays, dry_run: dryRun },
    "Failed to clean up proxy API keys",
  );
}
