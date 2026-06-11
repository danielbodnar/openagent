import { describe, expect, it } from "vitest";

import { deriveAssistantTurnStatus } from "./assistant-turn-status";

describe("deriveAssistantTurnStatus", () => {
  it("labels a clean success as 'Turn complete' with no resume button", () => {
    const status = deriveAssistantTurnStatus({ success: true });
    expect(status.label).toBe("Turn complete");
    expect(status.iconClass).toBe("text-emerald-400");
    expect(status.showResume).toBe(false);
  });

  it("labels a success inside a goal loop with the iteration number", () => {
    const status = deriveAssistantTurnStatus({
      success: true,
      goalIteration: 4,
    });
    expect(status.label).toBe("Iteration 4");
    expect(status.iconClass).toBe("text-emerald-400");
    expect(status.showResume).toBe(false);
  });

  it("ignores goalIteration === 0 (no active goal loop)", () => {
    const status = deriveAssistantTurnStatus({
      success: true,
      goalIteration: 0,
    });
    expect(status.label).toBe("Turn complete");
  });

  it("labels a genuine failure as 'Failed' with a resume affordance", () => {
    const status = deriveAssistantTurnStatus({
      success: false,
      terminalReason: "LlmError",
    });
    expect(status.label).toBe("Failed");
    expect(status.iconClass).toBe("text-red-400");
    expect(status.showResume).toBe(true);
  });

  it("renders ServerShutdown as an auto-resumed deploy interruption, not a failure", () => {
    // This is the regression we're closing: the user was seeing a red
    // 'Failed' + Resume button on every assistant message because their
    // own deployer missions were SIGTERM'ing the host. Auto-resume kicks
    // in server-side; the UI should reflect that.
    const status = deriveAssistantTurnStatus({
      success: false,
      terminalReason: "ServerShutdown",
    });
    expect(status.label).toBe("Interrupted by deploy — auto-resumed");
    expect(status.iconClass).toBe("text-indigo-400");
    expect(status.showResume).toBe(false);
  });

  it("does not treat ServerShutdown as success (terminalReason still wins over goalIteration)", () => {
    // Defensive: even if a stale goalIteration field hangs around on a
    // failed turn, the failure classification still applies.
    const status = deriveAssistantTurnStatus({
      success: false,
      terminalReason: "ServerShutdown",
      goalIteration: 3,
    });
    expect(status.label).toBe("Interrupted by deploy — auto-resumed");
    expect(status.showResume).toBe(false);
  });

  it("falls back to 'Failed' when terminalReason is missing on a failed turn", () => {
    // Older events recorded before completion_evidence existed have no
    // terminal_reason. They should keep the original 'Failed' pill so we
    // don't silently downgrade real failures to 'auto-resumed'.
    const status = deriveAssistantTurnStatus({ success: false });
    expect(status.label).toBe("Failed");
    expect(status.showResume).toBe(true);
  });

  it("renders InfiniteLoop as 'cut short' (model emitted a degenerate loop)", () => {
    // ab260b2e incident: Claude Code streamed "Yielding pending your
    // choice." for 50 minutes, costing $110.99, before the model hit
    // max_tokens. The backend's degenerate-stream detector now kills the
    // CLI and emits TerminalReason::InfiniteLoop with a partial output
    // summary. The user should see an amber 'cut short' pill (distinct
    // from generic red 'Failed') and a resume button — the next turn can
    // proceed without the loop.
    const status = deriveAssistantTurnStatus({
      success: false,
      terminalReason: "InfiniteLoop",
    });
    expect(status.label).toBe("Model entered a repetitive loop — cut short");
    expect(status.iconClass).toBe("text-amber-400");
    expect(status.showResume).toBe(true);
  });
});
