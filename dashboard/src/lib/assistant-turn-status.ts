/**
 * Per-turn status pill for an assistant chat bubble.
 *
 * Most turns are `Turn complete` (success) or `Failed` (genuine agent
 * failure). A `ServerShutdown` terminal reason means the API was SIGTERM'd
 * mid-turn — the mission auto-resumes from this server-side, so showing
 * the user a red "Failed" + Resume button is misleading. Render it as an
 * indigo "Interrupted by deploy — auto-resumed" pill instead, and suppress
 * the Resume button.
 *
 * `InfiniteLoop` means the backend's degenerate-stream detector killed the
 * CLI because the model was emitting the same short phrase in a tight loop
 * (the ab260b2e "Yielding pending your choice." incident). Render it as a
 * distinct amber "Model entered a repetitive loop — cut short" pill so the
 * user knows the model wasn't actually waiting on them and the cost was
 * contained.
 *
 * Pure function so the tests can verify the mapping without touching React.
 */
export function deriveAssistantTurnStatus(item: {
  success: boolean;
  terminalReason?: string;
  goalIteration?: number;
}): {
  label: string;
  iconClass: string;
  /** Whether the bubble should offer a "Resume Mission" button. */
  showResume: boolean;
} {
  if (item.success) {
    return {
      label:
        item.goalIteration && item.goalIteration > 0
          ? `Iteration ${item.goalIteration}`
          : "Turn complete",
      iconClass: "text-emerald-400",
      showResume: false,
    };
  }
  if (item.terminalReason === "ServerShutdown") {
    return {
      label: "Interrupted by deploy — auto-resumed",
      iconClass: "text-indigo-400",
      showResume: false,
    };
  }
  if (item.terminalReason === "InfiniteLoop") {
    return {
      label: "Model entered a repetitive loop — cut short",
      iconClass: "text-amber-400",
      showResume: true,
    };
  }
  return {
    label: "Failed",
    iconClass: "text-red-400",
    showResume: true,
  };
}
