'use client';

/**
 * WorkspaceResources — live memory usage + caps for a container workspace.
 *
 * Shows the aggregated memory consumption of the workspace's transient
 * mission scopes (boot + exec) and lets the operator retune the caps:
 * applied live to running scopes via `systemctl set-property --runtime`
 * and persisted as workspace env overrides (MISSION_MEMORY_*).
 */

import { useState } from 'react';
import useSWR from 'swr';
import { Gauge, Zap } from 'lucide-react';
import {
  getWorkspaceMemory,
  updateWorkspaceResources,
} from '@/lib/api';
import { cn } from '@/lib/utils';

function formatBytes(bytes: number | null): string {
  if (bytes === null || bytes === undefined) return '—';
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)}G`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toFixed(0)}M`;
  return `${bytes}B`;
}

const MEM_VALUE_RE = /^(\d+(\.\d+)?[KMGTkmgt]?|\d+(\.\d+)?%|infinity)?$/;

export function WorkspaceResources({ workspaceId }: { workspaceId: string }) {
  const { data: stats, mutate } = useSWR(
    `workspace-memory-${workspaceId}`,
    () => getWorkspaceMemory(workspaceId),
    { refreshInterval: 5000, revalidateOnFocus: false }
  );

  const [memoryHigh, setMemoryHigh] = useState('');
  const [memoryMax, setMemoryMax] = useState('');
  const [applying, setApplying] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const current = stats?.memory_current_bytes ?? null;
  const limit = stats?.memory_limit_bytes ?? null;
  const pct =
    current !== null && limit !== null && limit > 0
      ? Math.min(100, (current / limit) * 100)
      : null;

  const inputsValid =
    MEM_VALUE_RE.test(memoryHigh.trim()) && MEM_VALUE_RE.test(memoryMax.trim());
  const hasInput = memoryHigh.trim() !== '' || memoryMax.trim() !== '';

  const apply = async () => {
    if (!hasInput || !inputsValid) return;
    setApplying(true);
    setError(null);
    setResult(null);
    try {
      const res = await updateWorkspaceResources(workspaceId, {
        ...(memoryHigh.trim() !== '' ? { memory_high: memoryHigh.trim() } : {}),
        ...(memoryMax.trim() !== '' ? { memory_max: memoryMax.trim() } : {}),
        persist: true,
        apply_live: true,
      });
      if (res.failed_units.length > 0 && res.applied_units.length === 0) {
        setError(
          `Persisted, but live retune failed on ${res.failed_units.length} running scope(s) — caps apply at next boot. Check server logs.`
        );
      } else {
        setResult(
          res.applied_units.length > 0
            ? `Applied live to ${res.applied_units.length} scope(s)${
                res.failed_units.length > 0
                  ? ` (${res.failed_units.length} failed)`
                  : ''
              }; persisted for future boots.`
            : 'Persisted; no running scopes to retune (applies at next boot).'
        );
      }
      setMemoryHigh('');
      setMemoryMax('');
      mutate();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update resources');
    } finally {
      setApplying(false);
    }
  };

  // Revert both overrides to the global MISSION_MEMORY_* defaults. The API
  // treats an empty string as "clear this override" (vs. an absent field,
  // which leaves it unchanged), so we send explicit empties here.
  const resetDefaults = async () => {
    setApplying(true);
    setError(null);
    setResult(null);
    try {
      const res = await updateWorkspaceResources(workspaceId, {
        memory_high: '',
        memory_max: '',
        persist: true,
        apply_live: true,
      });
      if (res.failed_units.length > 0 && res.applied_units.length === 0) {
        setError(
          `Cleared overrides, but live retune failed on ${res.failed_units.length} running scope(s) — defaults apply at next boot. Check server logs.`
        );
      } else {
        setResult(
          res.applied_units.length > 0
            ? `Reset to defaults on ${res.applied_units.length} scope(s)${
                res.failed_units.length > 0
                  ? ` (${res.failed_units.length} failed)`
                  : ''
              }; cleared persisted overrides.`
            : 'Cleared persisted overrides (applies at next boot).'
        );
      }
      setMemoryHigh('');
      setMemoryMax('');
      mutate();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to reset resources');
    } finally {
      setApplying(false);
    }
  };

  return (
    <div className="rounded-lg bg-white/[0.02] border border-white/[0.05] p-3">
      <div className="flex items-center justify-between">
        <div>
          <p className="text-xs text-white/60 font-medium flex items-center gap-1.5">
            <Gauge className="h-3.5 w-3.5 text-white/40" />
            Resources
          </p>
          <p className="text-[10px] text-white/30 mt-0.5">
            Memory caps for this workspace&apos;s mission scopes. Changes apply live —
            no restart needed.
          </p>
        </div>
        <span className="text-xs font-mono text-white/70">
          {formatBytes(current)}
          {limit !== null && (
            <span className="text-white/35"> / {formatBytes(limit)}</span>
          )}
        </span>
      </div>

      {pct !== null && (
        <div className="mt-2 h-1.5 w-full rounded-full bg-white/[0.06] overflow-hidden">
          <div
            className={cn(
              'h-full rounded-full transition-all',
              pct > 90 ? 'bg-red-400/80' : pct > 75 ? 'bg-amber-400/70' : 'bg-emerald-400/60'
            )}
            style={{ width: `${pct}%` }}
          />
        </div>
      )}

      {stats?.memory_peak_bytes !== null && stats?.memory_peak_bytes !== undefined && (
        <p className="text-[10px] text-white/25 mt-1">
          Peak: {formatBytes(stats.memory_peak_bytes)}
        </p>
      )}

      {stats?.error && (
        <p className="text-[10px] text-white/30 mt-1.5">{stats.error}</p>
      )}

      <div className="mt-3 flex items-end gap-2">
        <div className="flex-1">
          <label className="text-[10px] text-white/40 block mb-1">
            MemoryHigh (throttle)
          </label>
          <input
            type="text"
            value={memoryHigh}
            onChange={(e) => setMemoryHigh(e.target.value)}
            placeholder="e.g. 20G"
            className="w-full rounded-lg border border-white/[0.06] bg-black/20 px-2.5 py-1.5 text-xs text-white font-mono placeholder:text-white/20 focus:border-indigo-500/50 focus:outline-none"
          />
        </div>
        <div className="flex-1">
          <label className="text-[10px] text-white/40 block mb-1">
            MemoryMax (OOM kill)
          </label>
          <input
            type="text"
            value={memoryMax}
            onChange={(e) => setMemoryMax(e.target.value)}
            placeholder="e.g. 24G"
            className="w-full rounded-lg border border-white/[0.06] bg-black/20 px-2.5 py-1.5 text-xs text-white font-mono placeholder:text-white/20 focus:border-indigo-500/50 focus:outline-none"
          />
        </div>
        <button
          onClick={apply}
          disabled={!hasInput || !inputsValid || applying}
          className={cn(
            'shrink-0 flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-medium transition-colors',
            hasInput && inputsValid && !applying
              ? 'bg-indigo-500/20 border border-indigo-500/30 text-indigo-200 hover:bg-indigo-500/30'
              : 'bg-white/[0.04] border border-white/[0.06] text-white/30 cursor-not-allowed'
          )}
        >
          <Zap className="h-3 w-3" />
          {applying ? 'Applying…' : 'Apply'}
        </button>
      </div>

      <button
        onClick={resetDefaults}
        disabled={applying}
        className="text-[10px] text-white/35 hover:text-white/60 mt-1.5 transition-colors disabled:cursor-not-allowed disabled:hover:text-white/35"
      >
        Reset to defaults
      </button>

      {!inputsValid && hasInput && (
        <p className="text-[10px] text-red-300/80 mt-1.5">
          Values: bytes with K/M/G/T suffix, %, or &quot;infinity&quot;.
        </p>
      )}
      {result && <p className="text-[10px] text-emerald-300/80 mt-1.5">{result}</p>}
      {error && <p className="text-[10px] text-red-300/80 mt-1.5">{error}</p>}
    </div>
  );
}
