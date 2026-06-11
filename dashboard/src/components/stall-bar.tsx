'use client';

import { AlertTriangle, Square } from 'lucide-react';
import { cn } from '@/lib/utils';

interface StallBarProps {
  /** Seconds since the agent last reported activity. */
  seconds: number;
  /** True once the stall is long enough to consider the agent stuck. */
  severe: boolean;
  /** Stop (or force-stop) the mission. */
  onStop: () => void;
  className?: string;
}

/**
 * Stall warning bar shown above the composer when the agent hasn't reported
 * activity for a while. Mirrors the GoalBar row grammar (rounded-md,
 * px-2.5 py-1.5, text-xs, tinted border/bg, mono status chip) so the bars
 * stacked over the input read as one family — amber while merely idle,
 * red once the agent looks stuck.
 */
export function StallBar({ seconds, severe, onStop, className }: StallBarProps) {
  return (
    <div
      className={cn(
        'flex w-full items-center gap-2 rounded-md border px-2.5 py-1.5 text-xs',
        severe
          ? 'border-red-500/25 bg-red-500/10'
          : 'border-amber-500/25 bg-amber-500/10',
        className,
      )}
      role="status"
      title={
        severe
          ? 'The agent appears to be stuck on a long-running operation. Consider stopping it.'
          : 'A tool or external operation may be taking longer than expected.'
      }
    >
      <AlertTriangle
        className={cn(
          'h-3.5 w-3.5 shrink-0',
          severe ? 'text-red-400' : 'text-amber-400',
        )}
      />
      <span
        className={cn(
          'shrink-0 font-medium',
          severe ? 'text-red-300' : 'text-amber-300',
        )}
      >
        {severe ? 'Likely stuck' : 'Idle'}
      </span>
      <span
        className={cn(
          'shrink-0 rounded px-1.5 py-0.5 font-mono text-[10px] tabular-nums',
          severe
            ? 'bg-red-500/15 text-red-300'
            : 'bg-amber-500/15 text-amber-300',
        )}
      >
        {Math.floor(seconds)}s
      </span>
      <button
        type="button"
        onClick={onStop}
        className={cn(
          'ml-auto inline-flex shrink-0 items-center gap-1 rounded border px-2 py-0.5 text-[11px] font-medium transition-colors',
          severe
            ? 'border-red-500/30 bg-red-500/15 text-red-400 hover:bg-red-500/25'
            : 'border-amber-500/30 bg-amber-500/15 text-amber-400 hover:bg-amber-500/25',
        )}
      >
        <Square className="h-3 w-3" />
        {severe ? 'Force stop' : 'Stop'}
      </button>
    </div>
  );
}
