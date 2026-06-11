'use client';

import { useState } from 'react';
import { Target, ChevronDown, ChevronUp, Square, Loader } from 'lucide-react';
import { cn } from '@/lib/utils';

interface GoalBarProps {
  /** The full goal objective text. */
  objective: string;
  /** Short status chip, e.g. "iter 2", "paused". */
  statusLabel: string;
  /** Whether the agent is currently mid-turn. Decides exit semantics: a
      running turn must be interrupted ("Stop goal"), an idle loop just gets
      deactivated ("End goal") without touching mission status. */
  running?: boolean;
  /** Stop/end the goal loop. The handler receives the running flag so it can
      pick the matching backend path. */
  onExit?: (running: boolean) => Promise<void> | void;
  className?: string;
}

// Beyond this length the objective is truncated in the collapsed row, so we
// offer click-to-expand. Shorter goals fit on one line and stay static (no
// dangling chevron on something already fully visible).
const EXPANDABLE_AT = 72;

/**
 * Goal-mode bar shown above the composer. Compact single line by default so it
 * never pushes the composer down; click to reveal the full wrapped objective.
 * Mirrors the QueueStrip pattern (indigo palette, chevron affordance).
 *
 * The expanded footer exposes a quiet exit affordance with an inline two-step
 * confirm (no browser dialogs): ghost at rest, explicit and red only once the
 * user has signalled intent. Copy adapts to whether a turn is in flight.
 */
export function GoalBar({
  objective,
  statusLabel,
  running = false,
  onExit,
  className,
}: GoalBarProps) {
  const [expanded, setExpanded] = useState(false);
  const [confirmingExit, setConfirmingExit] = useState(false);
  const [exiting, setExiting] = useState(false);

  const collapse = () => {
    setExpanded(false);
    setConfirmingExit(false);
  };

  const handleExitConfirmed = async (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!onExit || exiting) return;
    setExiting(true);
    try {
      await onExit(running);
    } finally {
      setExiting(false);
      setConfirmingExit(false);
    }
  };

  const renderHeader = (chevron?: React.ReactNode) => (
    <>
      <Target className="h-3.5 w-3.5 shrink-0 text-indigo-400" />
      <span className="shrink-0 font-medium text-indigo-300">Goal</span>
      <span className="shrink-0 rounded bg-indigo-500/15 px-1.5 py-0.5 font-mono text-[10px] text-indigo-300">
        {statusLabel}
      </span>
      {chevron}
    </>
  );

  const expandable = objective.length > EXPANDABLE_AT;

  // Static row — objective fits, nothing to expand.
  if (!expandable) {
    return (
      <div
        className={cn(
          'flex w-full items-center gap-2 rounded-md border border-indigo-500/25 bg-indigo-500/10 px-2.5 py-1.5 text-xs',
          className,
        )}
        role="status"
        title={objective}
      >
        {renderHeader()}
        {objective && (
          <span className="min-w-0 truncate text-white/50">{objective}</span>
        )}
      </div>
    );
  }

  // Expanded — full objective wrapped, scrollable if very long.
  if (expanded) {
    return (
      <div
        className={cn(
          'w-full rounded-md border border-indigo-500/25 bg-indigo-500/10 overflow-hidden',
          className,
        )}
        role="status"
      >
        <button
          type="button"
          onClick={collapse}
          className="flex w-full items-center gap-2 px-2.5 py-1.5 text-xs hover:bg-indigo-500/[0.06] transition-colors"
          title="Collapse goal"
        >
          {renderHeader(
            <ChevronUp className="ml-auto h-3.5 w-3.5 shrink-0 text-white/40" />,
          )}
        </button>
        <p className="max-h-40 overflow-y-auto border-t border-indigo-500/15 px-2.5 py-2 text-xs leading-relaxed text-white/70 whitespace-pre-wrap break-words">
          {objective}
        </p>
        {onExit && (
          <div className="flex min-h-[30px] items-center justify-between gap-2 border-t border-indigo-500/15 px-2.5 py-1.5">
            {exiting ? (
              <span className="ml-auto inline-flex items-center gap-1.5 text-[11px] text-white/50">
                <Loader className="h-3 w-3 animate-spin" />
                {running ? 'Stopping the loop and interrupting the turn' : 'Ending the loop'}
              </span>
            ) : confirmingExit ? (
              <>
                <span className="min-w-0 truncate text-[11px] text-white/45">
                  {running
                    ? 'Stops the loop and interrupts the current turn.'
                    : 'Ends the loop. The mission stays as it is.'}
                </span>
                <span className="flex shrink-0 items-center gap-1.5">
                  <button
                    type="button"
                    onClick={handleExitConfirmed}
                    className="inline-flex items-center gap-1 rounded border border-red-500/30 bg-red-500/15 px-2 py-0.5 text-[11px] font-medium text-red-400 transition-colors hover:bg-red-500/25 active:scale-[0.98]"
                  >
                    <Square className="h-3 w-3" />
                    {running ? 'Stop goal' : 'End goal'}
                  </button>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation();
                      setConfirmingExit(false);
                    }}
                    className="rounded px-1.5 py-0.5 text-[11px] text-white/40 transition-colors hover:bg-white/[0.06] hover:text-white/70"
                  >
                    Keep going
                  </button>
                </span>
              </>
            ) : (
              <button
                type="button"
                onClick={(e) => {
                  e.stopPropagation();
                  setConfirmingExit(true);
                }}
                className="ml-auto inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium text-white/40 transition-colors hover:bg-red-500/10 hover:text-red-300 active:scale-[0.98]"
                title={
                  running
                    ? 'Stop the goal loop (interrupts the current turn)'
                    : 'End the goal loop'
                }
              >
                <Square className="h-3 w-3" />
                Exit goal
              </button>
            )}
          </div>
        )}
      </div>
    );
  }

  // Collapsed — single line, click to expand.
  return (
    <div
      className={cn(
        'group flex w-full items-center gap-2 rounded-md border border-indigo-500/25 bg-indigo-500/10 px-2.5 py-1.5 text-xs transition-colors',
        'hover:border-indigo-500/35 hover:bg-indigo-500/[0.14] cursor-pointer select-none',
        className,
      )}
      role="button"
      tabIndex={0}
      onClick={() => setExpanded(true)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault();
          setExpanded(true);
        }
      }}
      title="Click to expand goal"
    >
      {renderHeader()}
      <span className="min-w-0 flex-1 truncate text-white/50">{objective}</span>
      <ChevronDown className="h-3.5 w-3.5 shrink-0 text-white/35 transition-transform group-hover:text-white/60" />
    </div>
  );
}
