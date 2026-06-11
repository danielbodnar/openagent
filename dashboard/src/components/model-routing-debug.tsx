'use client';

import { useState } from 'react';
import useSWR from 'swr';
import { Activity, RefreshCw, Zap, AlertCircle, CheckCircle2 } from 'lucide-react';
import { toast } from '@/components/toast';
import {
  listModelChains,
  resolveModelChain,
  listAccountHealth,
  clearAccountCooldown,
  listFallbackEvents,
  testModelChain,
  ModelChain,
  ResolvedEntry,
} from '@/lib/api/model-routing';

function fmtAgo(iso: string): string {
  const secs = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (secs < 60) return `${Math.round(secs)}s ago`;
  if (secs < 3600) return `${Math.round(secs / 60)}m ago`;
  return `${Math.round(secs / 3600)}h ago`;
}

function ChainRow({ chain }: { chain: ModelChain }) {
  const { data: resolved, mutate: mutateResolved } = useSWR<ResolvedEntry[]>(
    `resolve:${chain.id}`,
    () => resolveModelChain(chain.id),
    { revalidateOnFocus: false }
  );
  const { data: health, mutate: mutateHealth } = useSWR('routing-health', listAccountHealth, {
    refreshInterval: 5000,
  });
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<string | null>(null);

  const healthByAccount = new Map((health ?? []).map((h) => [h.account_id, h]));

  const runTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      // Goes through the JWT-authed test endpoint — the dashboard does not
      // hold a proxy bearer for /v1/chat/completions.
      const res = await testModelChain(chain.id);
      if (res.ok) {
        const msg = res.response.choices?.[0]?.message.content ?? '(no content)';
        setTestResult(`✅ ${(msg ?? '(no content)').slice(0, 80)}`);
      } else {
        const err = res.response.error?.message ?? `HTTP ${res.status}`;
        setTestResult(`❌ ${err.slice(0, 160)}`);
      }
      mutateResolved();
    } catch (e) {
      setTestResult(`❌ ${(e as Error).message}`);
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="rounded-lg border border-white/[0.06] bg-white/[0.02] p-3">
      <div className="flex items-center justify-between mb-2">
        <div>
          <div className="text-sm font-medium text-white">
            {chain.name}{' '}
            <span className="text-white/30 font-normal text-xs">({chain.id})</span>
          </div>
          <div className="text-xs text-white/40">
            {chain.entries.length} entries
          </div>
        </div>
        <button
          onClick={runTest}
          disabled={testing}
          className="flex items-center gap-1.5 rounded-md bg-indigo-500/15 px-2.5 py-1 text-xs text-indigo-300 hover:bg-indigo-500/25 transition-colors disabled:opacity-50"
          title="Send a 1-token test request through this chain"
        >
          <Zap className="h-3 w-3" />
          {testing ? 'Testing…' : 'Test chain'}
        </button>
      </div>

      <div className="space-y-1">
        {chain.entries.map((entry, idx) => {
          const r = resolved?.find(
            (x) => x.provider_id === entry.provider_id && x.model_id === entry.model_id
          );
          // /resolve omits accounts in cooldown, so fall back to matching
          // health by provider — otherwise cooled-down entries read as
          // "unresolved" with no way to clear the cooldown.  With several
          // accounts on the same provider we can't attribute the cooldown to
          // one of them, so "clear" targets every cooled account explicitly.
          const cooledMatches = !r
            ? (health ?? []).filter(
                (x) =>
                  x.provider_id === entry.provider_id &&
                  x.cooldown_remaining_secs != null
              )
            : [];
          const cooledFallback = cooledMatches[0];
          const h = r ? healthByAccount.get(r.account_id) : cooledFallback;
          const clearTargets = r ? (h ? [h.account_id] : []) : cooledMatches.map((x) => x.account_id);
          const skipped = !r && !cooledFallback;
          const inCooldown = h?.cooldown_remaining_secs != null;
          const noCredentials = r != null && !r.has_credentials;
          const degraded = h?.is_degraded === true;
          const ok = r != null && r.has_credentials && !inCooldown && !degraded;
          return (
            <div
              key={idx}
              className="flex items-center justify-between gap-2 rounded border border-white/[0.04] bg-black/20 px-2.5 py-1.5 text-xs"
            >
              <div className="flex items-center gap-2 min-w-0">
                <span className="text-white/30 tabular-nums">{idx + 1}.</span>
                <span className="text-white/80 truncate">
                  {entry.provider_id} / {entry.model_id}
                </span>
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                {skipped && (
                  <span className="flex items-center gap-1 text-amber-400">
                    <AlertCircle className="h-3 w-3" />
                    unresolved
                  </span>
                )}
                {noCredentials && (
                  <span className="flex items-center gap-1 text-amber-400">
                    <AlertCircle className="h-3 w-3" />
                    no credentials
                  </span>
                )}
                {degraded && !inCooldown && (
                  <span className="flex items-center gap-1 text-amber-400">
                    <AlertCircle className="h-3 w-3" />
                    degraded
                  </span>
                )}
                {inCooldown && clearTargets.length > 0 && (
                  <>
                    <span className="text-red-400">
                      cooldown {Math.round(h!.cooldown_remaining_secs!)}s
                      {cooledMatches.length > 1 && ` (${cooledMatches.length} accounts)`}
                    </span>
                    <button
                      onClick={async () => {
                        try {
                          await Promise.all(clearTargets.map((id) => clearAccountCooldown(id)));
                          // /resolve omits cooled accounts, so refresh it too —
                          // otherwise the entry keeps reading "unresolved".
                          await Promise.all([mutateHealth(), mutateResolved()]);
                          toast.success(
                            clearTargets.length > 1
                              ? `Cleared ${clearTargets.length} cooldowns`
                              : 'Cooldown cleared'
                          );
                        } catch (e) {
                          toast.error((e as Error).message);
                        }
                      }}
                      title={
                        cooledMatches.length > 1
                          ? `Clears all ${cooledMatches.length} cooled ${entry.provider_id} accounts`
                          : undefined
                      }
                      className="rounded bg-white/5 px-1.5 py-0.5 text-white/70 hover:bg-white/10"
                    >
                      {cooledMatches.length > 1 ? 'clear all' : 'clear'}
                    </button>
                  </>
                )}
                {ok && (
                  <span className="flex items-center gap-1 text-emerald-400">
                    <CheckCircle2 className="h-3 w-3" />
                    healthy
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {testResult && (
        <div className="mt-2 rounded bg-black/30 px-2 py-1 text-xs font-mono text-white/70">
          {testResult}
        </div>
      )}
    </div>
  );
}

export function ModelRoutingDebug() {
  const { data: chains, isLoading, mutate } = useSWR('routing-chains', listModelChains, {
    revalidateOnFocus: false,
  });
  const { data: events } = useSWR('routing-events', listFallbackEvents, {
    refreshInterval: 10000,
  });

  return (
    <section className="rounded-xl bg-white/[0.02] border border-white/[0.04] p-5">
      <div className="flex items-center gap-3 mb-4">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/10 flex-shrink-0">
          <Activity className="h-5 w-5 text-emerald-400" />
        </div>
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-medium text-white">Model Routing Debug</h2>
          <p className="text-xs text-white/40 truncate">
            Resolve chains, inspect cooldowns, replay test requests
          </p>
        </div>
        <button
          onClick={() => mutate()}
          className="flex h-8 w-8 items-center justify-center rounded-lg bg-white/5 text-white/60 hover:bg-white/10"
          title="Refresh"
        >
          <RefreshCw className="h-3.5 w-3.5" />
        </button>
      </div>

      {isLoading && <div className="text-xs text-white/40">Loading chains…</div>}

      <div className="space-y-2">
        {chains?.map((c) => (
          <ChainRow key={c.id} chain={c} />
        ))}
      </div>

      <div className="mt-5">
        <h3 className="text-xs font-medium text-white/60 mb-2">
          Recent fallback events
        </h3>
        {!events || events.length === 0 ? (
          <div className="text-xs text-white/30">
            No fallback events recorded.
          </div>
        ) : (
          <div className="space-y-1">
            {events.slice(-20).reverse().map((e, i) => (
              <div
                key={i}
                className="flex items-center justify-between gap-2 rounded border border-white/[0.04] bg-black/20 px-2.5 py-1.5 text-xs"
              >
                <div className="flex items-center gap-2 min-w-0">
                  <span className="text-white/40 tabular-nums flex-shrink-0">
                    {fmtAgo(e.timestamp)}
                  </span>
                  <span className="text-white/30">{e.chain_id}</span>
                  <span className="text-white/70 truncate">
                    {e.from_provider}/{e.from_model}
                    {e.to_provider && ` → ${e.to_provider}`}
                  </span>
                </div>
                <span className="text-amber-400 flex-shrink-0">{e.reason}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
