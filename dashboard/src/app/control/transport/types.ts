// Shared types for the control-page stream transport layer.

/** A parsed event delivered by the control SSE/WebSocket stream. */
export type ControlStreamEvent = { type: string; data: unknown };

/** UI-facing connection state for the stream indicator. */
export type StreamConnectionState =
  | "connected"
  | "disconnected"
  | "reconnecting";

/**
 * Handle returned by `createMissionStream`. Lets the consumer drive
 * reconnects (e.g. when switching the viewed mission) and feed connection
 * health back into the backoff state machine from inside its event handler.
 */
export interface MissionStreamHandle {
  /** Tear down the current connection (if any) and open a fresh one. */
  connect: () => void;
  /** Close the current connection without scheduling a reconnect. */
  closeConnection: () => void;
  /**
   * Reset the reconnect backoff after a healthy event (a `status` event).
   * Returns true when we were in a reconnecting state, so the caller can
   * trigger history catch-up for missed events.
   */
  markConnected: () => boolean;
  /** Schedule a reconnect with exponential backoff. */
  scheduleReconnect: () => void;
  /** Stop everything: cancel timers and close the connection. */
  dispose: () => void;
}
