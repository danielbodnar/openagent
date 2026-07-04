"use client";

// Thinking/streaming reasoning views: the inline transcript pill
// (ThinkingGroupItem) and the virtualized side panel (ThinkingPanel).
// Extracted mechanically from control-client.tsx.

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowDown,
  Brain,
  ChevronDown,
  ChevronUp,
  PenLine,
  X,
} from "lucide-react";
import { useVirtualizer } from "@tanstack/react-virtual";
import { StreamingMarkdown } from "@/components/streaming-markdown";
import { cn } from "@/lib/utils";
import { useVirtualTimelineAnchor } from "@/hooks/use-virtual-timeline-anchor";
import { LiveDuration, formatDuration, type SidePanelItem } from "./common";

export function ThinkingGroupItem({
  items,
  basePath,
  workspaceId,
  missionId,
  defaultExpanded = false,
}: {
  items: SidePanelItem[];
  basePath?: string;
  workspaceId?: string;
  missionId?: string;
  /**
   * When true (this is the last row in the transcript), the pill opens by
   * default so the live thought/draft is readable without a click. Once a
   * newer row arrives this flips back to false and — absent a manual
   * override — the group auto-collapses, keeping only the current tail open.
   */
  defaultExpanded?: boolean;
}) {
  // Filter out empty items for display
  const nonEmptyItems = useMemo(
    () => items.filter((item) => item.content.trim()),
    [items],
  );

  const hasActiveItem = items.some((item) => !item.done);
  // Expansion tracks `defaultExpanded` (the last transcript row opens; older
  // rows stay compact) until the user clicks, after which their explicit
  // choice sticks. `null` = follow the default.
  const [manualExpanded, setManualExpanded] = useState<boolean | null>(null);
  const expanded = manualExpanded ?? defaultExpanded;

  // Get the earliest start time and latest end time
  const startTime = Math.min(...items.map((item) => item.startTime));
  const endTime = items.every((item) => item.done && item.endTime)
    ? Math.max(...items.map((item) => item.endTime || item.startTime))
    : undefined;

  // Only the active branch ticks once per second via `<LiveDuration>`.
  // When the group is fully done, we render a fixed string and never
  // subscribe to `useNow()`.
  const doneDuration =
    !hasActiveItem && endTime
      ? formatDuration(Math.floor((endTime - startTime) / 1000))
      : null;

  // Nothing to show only when there is no content AND nothing in flight.
  // An active group with still-empty content keeps its liveness pill —
  // previously it rendered nothing, which also suppressed the "Agent is
  // working" pill and left a dead gap in the transcript.
  if (nonEmptyItems.length === 0 && !hasActiveItem) {
    return null;
  }

  const label = (() => {
    const hasStream = nonEmptyItems.some((item) => item.kind === "stream");
    const hasThinking = nonEmptyItems.some((item) => item.kind === "thinking");
    if (hasStream && !hasThinking) {
      return nonEmptyItems.length === 1 ? "Draft" : "Drafts";
    }
    return nonEmptyItems.length === 1 ? "Thought" : "Thoughts";
  })();

  const activeLabel = (() => {
    if (items.some((item) => !item.done && item.kind === "thinking")) {
      return "Thinking";
    }
    if (items.some((item) => !item.done && item.kind === "stream")) {
      return "Streaming";
    }
    return "Thinking";
  })();

  // Streaming the final response (kind "stream") is not reasoning — give it a
  // writing glyph so the brain icon stays reserved for actual thoughts.
  const isStreamView = hasActiveItem
    ? activeLabel === "Streaming"
    : label === "Draft" || label === "Drafts";
  const HeaderIcon = isStreamView ? PenLine : Brain;

  return (
    <div className="my-2">
      {/* Compact header */}
      <button
        onClick={() => setManualExpanded(!expanded)}
        className={cn(
          "flex items-center gap-1.5 px-2.5 py-1 rounded-full",
          "bg-white/[0.04] border border-white/[0.06]",
          "text-white/40 hover:text-white/60 hover:bg-white/[0.06]",
          "transition-all duration-200",
        )}
      >
        <HeaderIcon
          className={cn(
            "h-3 w-3",
            hasActiveItem && "animate-pulse text-indigo-400",
          )}
        />
        <span className="text-xs">
          {hasActiveItem ? (
            <>
              {activeLabel} for <LiveDuration startTime={startTime} />
            </>
          ) : (
            `${label} for ${doneDuration ?? "<1s"}`
          )}
        </span>
        {nonEmptyItems.length > 1 && (
          <span className="text-xs text-white/30">
            ({nonEmptyItems.length})
          </span>
        )}
        <ChevronDown
          className={cn(
            "h-3 w-3 transition-transform duration-200",
            expanded ? "rotate-0" : "-rotate-90",
          )}
        />
      </button>

      {/* Expandable content with animation */}
      <div
        className={cn(
          "overflow-hidden transition-all duration-200 ease-out",
          expanded ? "max-h-[50vh] opacity-100 mt-2" : "max-h-0 opacity-0",
        )}
      >
        <div className="rounded-lg border border-white/[0.06] bg-white/[0.02] p-3">
          <div className="overflow-y-auto max-h-[45vh] leading-relaxed space-y-2">
            {nonEmptyItems.map((item, idx) => (
              <div key={item.id}>
                {idx > 0 && (
                  <div className="border-t border-white/[0.06] my-2" />
                )}
                {/* Use StreamingMarkdown for efficient incremental rendering */}
                <StreamingMarkdown
                  content={item.content}
                  isStreaming={!item.done}
                  className="text-xs text-white/60 [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1"
                  basePath={basePath}
                  workspaceId={workspaceId}
                  missionId={missionId}
                />
              </div>
            ))}
            {hasActiveItem && nonEmptyItems.length === 0 && (
              <span className="italic text-white/30">Processing...</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// Thinking panel item - simplified version for side panel
// Threshold for collapsing long thoughts (in characters)
const THOUGHT_COLLAPSE_THRESHOLD = 800;

const ThinkingPanelItem = memo(function ThinkingPanelItem({
  item,
  isActive,
  basePath,
  workspaceId,
  missionId,
}: {
  item: SidePanelItem;
  isActive: boolean;
  basePath?: string;
  workspaceId?: string;
  missionId?: string;
}) {
  // P1-#7 / re-render fix: only active items live-tick via `<LiveDuration>`.
  // Done items render a fixed string and never subscribe to `useNow()`, so
  // visible done cards no longer commit once per second forever.
  const [isExpanded, setIsExpanded] = useState(!item.done);

  const doneDuration =
    item.done && item.endTime
      ? formatDuration(Math.floor((item.endTime - item.startTime) / 1000))
      : null;

  const activeLabel = item.kind === "stream" ? "Streaming" : "Thinking";
  const pastLabel = item.kind === "stream" ? "Draft" : "Thought";
  const ItemIcon = item.kind === "stream" ? PenLine : Brain;

  // For completed items, check if content is long enough to collapse
  const isLongContent =
    !isActive && item.content.length > THOUGHT_COLLAPSE_THRESHOLD;
  const shouldTruncate = isLongContent && !isExpanded;

  // Get truncated content for display
  const displayContent = shouldTruncate
    ? item.content.slice(0, THOUGHT_COLLAPSE_THRESHOLD) + "..."
    : item.content;

  return (
    <div
      className={cn(
        "rounded-lg border p-3",
        // Unified styling - subtle border highlight for active, same base appearance
        isActive
          ? "border-indigo-500/30 bg-white/[0.02]"
          : "border-white/[0.06] bg-white/[0.02]",
      )}
    >
      <div className="flex items-center gap-2 mb-2">
        <ItemIcon
          className={cn(
            "h-3.5 w-3.5 shrink-0",
            isActive ? "animate-pulse text-indigo-400" : "text-white/40",
          )}
        />
        <span
          className={cn(
            "text-xs font-medium",
            isActive ? "text-indigo-400" : "text-white/50",
          )}
        >
          {isActive ? (
            <>
              {activeLabel} for <LiveDuration startTime={item.startTime} />
            </>
          ) : (
            `${pastLabel} for ${doneDuration ?? "<1s"}`
          )}
        </span>
      </div>
      {/* Content area - no internal scroll, unified text color */}
      <div className="text-xs leading-relaxed text-white/60">
        {item.content ? (
          <>
            <StreamingMarkdown
              content={displayContent}
              isStreaming={isActive}
              className="text-xs [&_p]:my-1 [&_ul]:my-1 [&_ol]:my-1"
              basePath={basePath}
              workspaceId={workspaceId}
              missionId={missionId}
            />
            {/* Expand/collapse button for long content */}
            {isLongContent && (
              <button
                onClick={() => setIsExpanded(!isExpanded)}
                className="mt-2 text-[10px] text-indigo-400/70 hover:text-indigo-400 transition-colors flex items-center gap-1"
              >
                {isExpanded ? (
                  <>
                    <ChevronUp className="h-3 w-3" />
                    Show less
                  </>
                ) : (
                  <>
                    <ChevronDown className="h-3 w-3" />
                    Show more (
                    {Math.round(
                      (item.content.length - THOUGHT_COLLAPSE_THRESHOLD) / 100,
                    ) * 100}
                    + chars)
                  </>
                )}
              </button>
            )}
          </>
        ) : (
          <span className="italic text-white/30">Processing...</span>
        )}
      </div>
    </div>
  );
});

// Thinking side panel component.
//
// `React.memo` short-circuits when props are reference-stable, so the panel
// no longer re-renders on chat-only updates. The two non-trivial inputs:
//   - `items`: kept reference-stable upstream via `useStableShallowArray`
//   - `onClose`: already wrapped in `useCallback`
// `className` is built from primitive string literals at the call site;
// `basePath` and `missionId` come from memoized values / the store.
export const ThinkingPanel = memo(function ThinkingPanel({
  items,
  onClose,
  className,
  basePath,
  missionId,
}: {
  items: SidePanelItem[];
  onClose: () => void;
  className?: string;
  basePath?: string;
  missionId?: string | null;
}) {
  const hasOpenModalOverlay = useCallback((): boolean => {
    const overlays = Array.from(
      document.querySelectorAll("body > div.fixed.inset-0"),
    );
    return overlays.some((overlay) => {
      const classText = overlay.className;
      if (
        !classText.includes("items-center") &&
        !classText.includes("items-start")
      ) {
        return false;
      }
      const zIndex = Number.parseInt(
        window.getComputedStyle(overlay).zIndex || "0",
        10,
      );
      return Number.isFinite(zIndex) && zIndex >= 50;
    });
  }, []);

  const activeItems = useMemo(() => items.filter((t) => !t.done), [items]);
  const hasActiveThinking = activeItems.some((i) => i.kind === "thinking");
  const hasActiveStream = activeItems.some((i) => i.kind === "stream");
  // Brain for thinking; pen when the only thing in flight is a streamed reply.
  const PanelHeaderIcon =
    hasActiveStream && !hasActiveThinking ? PenLine : Brain;

  // Performance: limit visible thoughts, load more on demand
  const scrollRef = useRef<HTMLDivElement>(null);
  const panelRows = useMemo(() => {
    const seenDoneContent = new Set<string>();
    return items
      .filter((item) => {
        const trimmed = item.content.trim();
        if (!item.done) return true;
        if (!trimmed) return false;
        if (seenDoneContent.has(trimmed)) return false;
        seenDoneContent.add(trimmed);
        return true;
      })
      .map((item) => ({ item }));
  }, [items]);
  const thoughtsAnchorKey = useMemo(
    () =>
      panelRows
        .slice(-8)
        .map(
          ({ item }) =>
            `${item.id}:${item.done ? "done" : "active"}:${item.content.length}`,
        )
        .join("|"),
    [panelRows],
  );
  const thoughtsVirtualizer = useVirtualizer({
    count: panelRows.length,
    getScrollElement: () => scrollRef.current,
    getItemKey: (index) => {
      const row = panelRows[index];
      if (!row) return index;
      return row.item.id;
    },
    estimateSize: (index) => {
      const row = panelRows[index];
      if (!row) return 96;
      return row.item.kind === "stream" ? 140 : 112;
    },
    overscan: 6,
  });
  // See `chatVirtualizer` below for rationale.
  thoughtsVirtualizer.shouldAdjustScrollPositionOnItemSizeChange = () => false;
  const {
    isAtBottom: isThoughtsAtBottom,
    scrollToBottom: scrollThoughtsToBottom,
    registerContent: registerThoughtsContent,
  } = useVirtualTimelineAnchor({
    scrollElementRef: scrollRef,
    virtualizer: thoughtsVirtualizer,
    itemCount: panelRows.length,
    changeKey: thoughtsAnchorKey,
    resetKey: missionId ?? null,
  });
  useEffect(() => {
    if (panelRows.length > 1) return;
    const forceBottom = () => {
      scrollThoughtsToBottom("auto");
      const el = scrollRef.current;
      if (el) el.scrollTop = el.scrollHeight;
    };
    const frame = requestAnimationFrame(forceBottom);
    const timeout = window.setTimeout(forceBottom, 250);
    return () => {
      cancelAnimationFrame(frame);
      window.clearTimeout(timeout);
    };
  }, [panelRows.length, scrollThoughtsToBottom, thoughtsAnchorKey]);

  // Handle Escape key
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        if (hasOpenModalOverlay()) return;
        onClose();
      }
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [hasOpenModalOverlay, onClose]);

  return (
    <div
      className={cn(
        "w-full h-full flex flex-col rounded-2xl glass-panel border border-white/[0.06] overflow-hidden animate-slide-in-right",
        className,
      )}
    >
      {/* Header */}
      <div className="flex items-center justify-between border-b border-white/[0.06] px-4 py-3">
        <div className="flex items-center gap-2">
          <PanelHeaderIcon
            className={cn(
              "h-4 w-4",
              activeItems.length > 0
                ? "animate-pulse text-indigo-400"
                : "text-white/40",
            )}
          />
          <span className="text-sm font-medium text-white">
            {hasActiveThinking
              ? "Thinking"
              : hasActiveStream
                ? "Streaming"
                : "Thoughts"}
          </span>
          {panelRows.length > 0 && (
            <span className="text-xs text-white/30">({panelRows.length})</span>
          )}
        </div>
        <button
          onClick={onClose}
          className="flex h-6 w-6 items-center justify-center rounded-lg text-white/40 hover:bg-white/[0.04] hover:text-white transition-colors"
        >
          <X className="h-3.5 w-3.5" />
        </button>
      </div>

      {/* Content - flex-col with overflow, scrolls up for history */}
      <div
        ref={scrollRef}
        data-testid="thoughts-scroll-container"
        className="relative flex-1 overflow-y-auto p-3"
      >
        {items.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-center p-4">
            <Brain className="h-8 w-8 text-white/20 mb-3" />
            <p className="text-sm text-white/40">No thoughts yet</p>
            <p className="text-xs text-white/30 mt-1">
              Agent reasoning will appear here
            </p>
          </div>
        ) : (
          <>
            <div
              ref={registerThoughtsContent}
              className="relative w-full"
              style={{
                height: `${thoughtsVirtualizer.getTotalSize()}px`,
                minHeight: "100%",
              }}
            >
              {thoughtsVirtualizer.getVirtualItems().map((virtualRow) => {
                const row = panelRows[virtualRow.index];
                if (!row) return null;
                return (
                  <div
                    key={virtualRow.key}
                    ref={thoughtsVirtualizer.measureElement}
                    data-index={virtualRow.index}
                    className="absolute left-0 top-0 w-full pb-3"
                    style={{
                      transform: `translateY(${virtualRow.start}px)`,
                    }}
                  >
                    <ThinkingPanelItem
                      item={row.item}
                      isActive={!row.item.done}
                      basePath={basePath}
                      missionId={missionId ?? undefined}
                    />
                  </div>
                );
              })}
            </div>
            {!isThoughtsAtBottom && (
              <button
                type="button"
                onClick={() => scrollThoughtsToBottom()}
                className="absolute bottom-3 right-3 inline-flex items-center gap-2 rounded-full border border-white/[0.12] bg-white/90 px-3 py-2 text-xs font-medium text-slate-700 shadow-lg backdrop-blur transition-all hover:bg-white hover:text-slate-950 dark:border-white/[0.1] dark:bg-black/70 dark:text-white/65 dark:hover:bg-white/[0.1] dark:hover:text-white/90"
                title="Scroll to bottom"
              >
                <ArrowDown className="h-4 w-4" />
                Auto-scroll paused
              </button>
            )}
          </>
        )}
      </div>
    </div>
  );
});
