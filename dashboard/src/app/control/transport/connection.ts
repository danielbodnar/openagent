// Connection lifecycle for the control event stream (SSE with WebSocket
// upgrade, both handled inside `streamControl`). Extracted mechanically from
// control-client.tsx: this module owns connect/teardown, the exponential
// reconnect backoff, the connection-generation guard, and the global-stream
// mission filter. Event *interpretation* (reducing events into chat items)
// stays with the consumer via `onEvent`.

import { streamControl, type StreamDiagnosticUpdate } from "@/lib/api";
import { isRecord } from "../events-reducer";
import type { ControlStreamEvent, MissionStreamHandle } from "./types";

export type {
  ControlStreamEvent,
  MissionStreamHandle,
  StreamConnectionState,
} from "./types";

export type StreamLogLevel = "debug" | "info" | "warn" | "error";

export function streamLog(
  level: StreamLogLevel,
  message: string,
  meta?: Record<string, unknown>,
) {
  const prefix = "[control:sse]";
  const args = meta ? [prefix, message, meta] : [prefix, message];
  switch (level) {
    case "debug":
      console.debug(...args);
      break;
    case "info":
      console.info(...args);
      break;
    case "warn":
      console.warn(...args);
      break;
    case "error":
      console.error(...args);
      break;
  }
}

export interface MissionStreamOptions {
  /**
   * Mission id to scope the stream to (the viewed mission), read both at
   * connect time (to pick the stream URL) and at event time (to drop
   * mission-scoped events arriving on a stale global stream).
   */
  getMissionFilter: () => string | null | undefined;
  /** Resume cursor for a mission-scoped stream (max seen event seq). */
  getSinceSeq: (missionId: string) => number | undefined;
  /** Receives every event that passes the transport-level filters. */
  onEvent: (event: ControlStreamEvent) => void;
  /** Raw transport diagnostics (bytes, phases, headers). */
  onDiagnostics: (update: StreamDiagnosticUpdate) => void;
  /** Called when a reconnect is scheduled, with the 1-based attempt count. */
  onReconnecting: (attempt: number) => void;
}

const MAX_RECONNECT_DELAY_MS = 30000;
const BASE_RECONNECT_DELAY_MS = 1000;

/**
 * Create the auto-reconnecting control stream.
 *
 * Connects immediately unless the page URL has a `?mission=` param that the
 * caller hasn't resolved into a mission filter yet (in that case the caller
 * triggers `connect()` once the mission is loaded, via the mission-switcher
 * effect).
 */
export function createMissionStream(
  opts: MissionStreamOptions,
): MissionStreamHandle {
  let cleanup: (() => void) | null = null;
  let reconnectTimeout: ReturnType<typeof setTimeout> | null = null;
  let reconnectAttempts = 0;
  let connectionGeneration = 0;
  let mounted = true;

  const scheduleReconnect = () => {
    if (!mounted) return;
    const delay = Math.min(
      BASE_RECONNECT_DELAY_MS * Math.pow(2, reconnectAttempts),
      MAX_RECONNECT_DELAY_MS,
    );
    reconnectAttempts++;
    streamLog("warn", "reconnect scheduled", {
      attempt: reconnectAttempts,
      delayMs: delay,
    });
    // Let the UI show the reconnecting indicator.
    opts.onReconnecting(reconnectAttempts);
    reconnectTimeout = setTimeout(() => {
      if (mounted) connect();
    }, delay);
  };

  const connect = () => {
    cleanup?.();
    const generation = ++connectionGeneration;
    const missionFilter = opts.getMissionFilter() ?? undefined;
    streamLog("info", "connecting stream", { missionFilter });
    cleanup = streamControl(
      (event) => {
        if (generation !== connectionGeneration) return;
        const data = event.data;
        const eventMissionId =
          isRecord(data) && data["mission_id"]
            ? String(data["mission_id"])
            : null;
        // On a global (unfiltered) stream, drop mission-scoped events once
        // the user is viewing a specific mission — the mission-scoped
        // reconnect will deliver them with proper sequencing.
        if (!missionFilter && opts.getMissionFilter() && eventMissionId) {
          return;
        }
        opts.onEvent(event);
      },
      opts.onDiagnostics,
      {
        missionId: missionFilter,
        sinceSeq: missionFilter ? opts.getSinceSeq(missionFilter) : undefined,
      },
    );
  };

  const initialUrlMission =
    typeof window !== "undefined"
      ? new URLSearchParams(window.location.search).get("mission")
      : null;
  if (!initialUrlMission || opts.getMissionFilter()) {
    connect();
  }

  return {
    connect,
    closeConnection: () => {
      cleanup?.();
      cleanup = null;
    },
    markConnected: () => {
      const wasReconnecting = reconnectAttempts > 0;
      reconnectAttempts = 0;
      return wasReconnecting;
    },
    scheduleReconnect,
    dispose: () => {
      mounted = false;
      if (reconnectTimeout) clearTimeout(reconnectTimeout);
      cleanup?.();
      cleanup = null;
    },
  };
}
