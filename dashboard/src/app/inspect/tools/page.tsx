'use client';

import { useMemo } from 'react';
import useSWR from 'swr';
import { Wrench } from 'lucide-react';
import { listTools, type ToolInfo } from '@/lib/api';
import { cn } from '@/lib/utils';

const DESKTOP_TOOL_GRID =
  'lg:grid lg:grid-cols-[minmax(180px,1.1fr)_minmax(160px,0.8fr)_minmax(280px,2fr)_minmax(110px,0.6fr)] lg:gap-4';

function formatToolSource(source: ToolInfo['source']): string {
  if (source === 'builtin') return 'Built-in';
  if (typeof source === 'object' && source && 'mcp' in source) {
    const name = source.mcp.name || source.mcp.id;
    return `MCP: ${name}`;
  }
  if (typeof source === 'object' && source && 'plugin' in source) {
    const name = source.plugin.name || source.plugin.id;
    return `Plugin: ${name}`;
  }
  return 'Unknown';
}

function ToolStatusBadge({ enabled }: { enabled: boolean }) {
  return (
    <span
      className={cn(
        'text-xs font-medium',
        enabled ? 'text-emerald-400' : 'text-white/40',
      )}
    >
      {enabled ? 'Enabled' : 'Disabled'}
    </span>
  );
}

function ToolRow({ tool }: { tool: ToolInfo }) {
  const sourceLabel = formatToolSource(tool.source);

  return (
    <>
      <div className="space-y-2 border-b border-white/[0.04] px-4 py-3 lg:hidden">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 font-medium text-white">{tool.name}</div>
          <ToolStatusBadge enabled={tool.enabled} />
        </div>
        <div className="text-xs text-white/60">{sourceLabel}</div>
        {tool.description ? (
          <div className="text-xs text-white/50">{tool.description}</div>
        ) : null}
      </div>

      <div
        className={cn(
          'hidden border-b border-white/[0.04] px-4 py-3 text-sm lg:grid',
          DESKTOP_TOOL_GRID,
        )}
      >
        <div className="truncate font-medium text-white">{tool.name}</div>
        <div className="truncate text-xs text-white/60">{sourceLabel}</div>
        <div className="line-clamp-2 text-xs text-white/50">{tool.description}</div>
        <ToolStatusBadge enabled={tool.enabled} />
      </div>
    </>
  );
}

function ToolRowSkeleton({ index }: { index: number }) {
  return (
    <>
      <div
        key={`mobile-${index}`}
        className="space-y-2 border-b border-white/[0.04] px-4 py-3 lg:hidden"
      >
        <div className="h-4 w-32 rounded bg-white/[0.06]" />
        <div className="h-4 w-24 rounded bg-white/[0.04]" />
        <div className="h-4 w-full rounded bg-white/[0.04]" />
      </div>
      <div
        key={`desktop-${index}`}
        className={cn(
          'hidden border-b border-white/[0.04] px-4 py-3 lg:grid',
          DESKTOP_TOOL_GRID,
        )}
      >
        <div className="h-4 w-32 rounded bg-white/[0.06]" />
        <div className="h-4 w-24 rounded bg-white/[0.04]" />
        <div className="h-4 w-full rounded bg-white/[0.04]" />
        <div className="h-4 w-16 rounded bg-white/[0.04]" />
      </div>
    </>
  );
}

export default function ToolsPage() {
  const { data: tools = [], isLoading: loading } = useSWR(
    'tools',
    listTools,
    { revalidateOnFocus: false },
  );

  const sortedTools = useMemo(() => {
    return [...tools].sort((a, b) => a.name.localeCompare(b.name));
  }, [tools]);

  return (
    <div className="mx-auto flex min-h-[calc(100vh-3rem)] max-w-6xl flex-col space-y-4 p-6 lg:min-h-screen">
      <div>
        <h1 className="text-2xl font-semibold text-white">Tools</h1>
        <p className="mt-1 text-sm text-white/60">
          Read-only inventory of tools available to agents, including their source.
        </p>
      </div>

      <div className="overflow-hidden rounded-xl border border-white/[0.06] bg-white/[0.02]">
        <div
          className={cn(
            'hidden border-b border-white/[0.06] px-4 py-3 text-[11px] uppercase tracking-wider text-white/40 lg:grid',
            DESKTOP_TOOL_GRID,
          )}
        >
          <span>Name</span>
          <span>Source</span>
          <span>Description</span>
          <span>Status</span>
        </div>

        {loading ? (
          <div>
            {Array.from({ length: 10 }).map((_, index) => (
              <ToolRowSkeleton key={index} index={index} />
            ))}
          </div>
        ) : sortedTools.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-16 text-white/40">
            <Wrench className="mb-3 h-10 w-10 text-white/20" />
            <p className="text-sm">No tools available</p>
          </div>
        ) : (
          <div>
            {sortedTools.map((tool) => (
              <ToolRow key={tool.name} tool={tool} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
