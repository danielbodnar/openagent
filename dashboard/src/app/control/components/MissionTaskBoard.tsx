"use client";

// Task board section for the mission workbench. Polls the server-owned board
// (see backend api::control::board) and renders task status, dependencies,
// and worker links. Renders nothing for missions without a board.

import { useCallback, useEffect, useRef, useState } from "react";
import { KanbanSquare, X } from "lucide-react";
import { cn } from "@/lib/utils";
import {
  cancelBoardTask,
  getMissionBoard,
  postBoardTaskVerdict,
  type BoardTask,
  type MissionBoard,
} from "@/lib/api";

const POLL_MS = 5000;

function statusGlyph(task: BoardTask): { glyph: string; cls: string } {
  switch (task.status) {
    case "running":
      return { glyph: "●", cls: "text-emerald-400" };
    case "settled":
      return task.outcome === "blocked"
        ? { glyph: "◆", cls: "text-orange-300" }
        : task.outcome === "failed"
          ? { glyph: "✗", cls: "text-red-400" }
          : { glyph: "◐", cls: "text-amber-300" };
    case "accepted":
      return { glyph: "✓", cls: "text-white/30" };
    case "failed":
      return { glyph: "✗", cls: "text-red-400" };
    case "cancelled":
      return { glyph: "–", cls: "text-white/25" };
    default:
      return { glyph: "○", cls: "text-white/40" };
  }
}

function rowTextClass(task: BoardTask): string {
  switch (task.status) {
    case "accepted":
      return "text-white/30 line-through";
    case "cancelled":
      return "text-white/25 line-through";
    case "running":
      return "text-white/80";
    case "settled":
      return task.outcome === "success" ? "text-amber-200/90" : "text-orange-300";
    case "failed":
      return "text-red-400";
    default:
      return "text-white/55";
  }
}

export function MissionTaskBoard({
  missionId,
  onViewMission,
}: {
  missionId: string;
  onViewMission: (missionId: string) => void;
}) {
  const [board, setBoard] = useState<MissionBoard | null>(null);
  const [busyTaskId, setBusyTaskId] = useState<string | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const refresh = useCallback(async () => {
    try {
      const b = await getMissionBoard(missionId);
      setBoard(b);
    } catch {
      // Board endpoint unavailable (old backend) or mission gone: hide section.
      setBoard(null);
    }
  }, [missionId]);

  useEffect(() => {
    setBoard(null);
    void refresh();
    timerRef.current = setInterval(() => void refresh(), POLL_MS);
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [refresh]);

  if (!board || board.tasks.length === 0) return null;
  const u = board.utilization;
  const done = u.accepted + u.cancelled;

  return (
    <div className="mt-3 border-t border-white/[0.06] pt-2.5">
      <div className="mb-1.5 flex items-center justify-between">
        <div className="flex items-center gap-1.5 text-[10px] font-semibold uppercase tracking-wide text-white/40">
          <KanbanSquare className="h-3 w-3" />
          Task board
        </div>
        <span
          className="text-[10px] tabular-nums text-white/35"
          title={`${u.running} running · ${u.pending} pending · ${u.settled} awaiting verdict · ${u.failed} failed · capacity ${u.max_parallel}`}
        >
          {u.running > 0 && (
            <span className="text-emerald-400">{u.running} running · </span>
          )}
          {u.settled > 0 && (
            <span className="text-amber-300">{u.settled} verdict · </span>
          )}
          {done}/{u.total} done
        </span>
      </div>
      <ul className="space-y-0.5">
        {board.tasks.map((task) => {
          const { glyph, cls } = statusGlyph(task);
          const clickable = !!task.worker_mission_id;
          const blockedBy =
            task.status === "pending" && task.depends_on.length > 0
              ? task.depends_on.join(", ")
              : null;
          return (
            <li key={task.id} className="group flex items-start gap-1.5">
              <span className={cn("mt-px shrink-0 text-[11px]", cls)}>
                {glyph}
              </span>
              <button
                type="button"
                disabled={!clickable}
                onClick={() =>
                  task.worker_mission_id &&
                  onViewMission(task.worker_mission_id)
                }
                title={[
                  `${task.task_key} · ${task.status}${task.outcome ? ` (${task.outcome})` : ""} · ${task.backend}${task.model_override ? ` ${task.model_override}` : ""}${task.attempts > 1 ? ` · attempt ${task.attempts}` : ""}`,
                  task.result_digest ? `\n${task.result_digest.slice(0, 500)}` : "",
                ].join("")}
                className={cn(
                  "min-w-0 flex-1 text-left text-[11px] leading-snug",
                  rowTextClass(task),
                  clickable && "hover:underline",
                )}
              >
                <span className="font-mono text-[10px] opacity-70">
                  {task.task_key}
                </span>{" "}
                {task.title}
                {blockedBy && (
                  <span className="text-[10px] text-white/30"> ← {blockedBy}</span>
                )}
              </button>
              {(task.status === "pending" || task.status === "running") && (
                <button
                  type="button"
                  title="Cancel task"
                  disabled={busyTaskId === task.id}
                  onClick={async () => {
                    setBusyTaskId(task.id);
                    try {
                      await cancelBoardTask(task.id);
                      await refresh();
                    } catch {
                      // surfaced by next poll
                    } finally {
                      setBusyTaskId(null);
                    }
                  }}
                  className="hidden shrink-0 rounded p-0.5 text-white/30 hover:bg-white/[0.06] hover:text-red-400 group-hover:block"
                >
                  <X className="h-3 w-3" />
                </button>
              )}
              {task.status === "settled" && (
                <button
                  type="button"
                  title="Accept result (boss normally judges; this overrides)"
                  disabled={busyTaskId === task.id}
                  onClick={async () => {
                    setBusyTaskId(task.id);
                    try {
                      await postBoardTaskVerdict(task.id, "accept");
                      await refresh();
                    } catch {
                      // surfaced by next poll
                    } finally {
                      setBusyTaskId(null);
                    }
                  }}
                  className="hidden shrink-0 rounded p-0.5 text-white/30 hover:bg-white/[0.06] hover:text-emerald-400 group-hover:block"
                >
                  ✓
                </button>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
