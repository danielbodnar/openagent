import { describe, expect, it } from "vitest";

import fixtures from "../../../../shared/control-reducer-fixtures.json";
import type { Mission, StoredEvent } from "@/lib/api";
import { eventsToItemsImpl, type ChatItem } from "./events-reducer";

type ExpectedItem = Partial<ChatItem> & { kind: ChatItem["kind"] };

function storedEvent(
  sequence: number,
  event_type: string,
  content: string,
  timestamp = `2026-05-28T10:00:${String(sequence).padStart(2, "0")}Z`,
  metadata: Record<string, unknown> = {},
): StoredEvent {
  return {
    id: sequence,
    mission_id: "mission-1",
    sequence,
    event_type,
    timestamp,
    content,
    metadata,
  };
}

function expectItemsContain(items: ChatItem[], expected: ExpectedItem[]) {
  for (const expectedItem of expected) {
    expect(items).toEqual(
      expect.arrayContaining([expect.objectContaining(expectedItem)]),
    );
  }
}

describe("eventsToItemsImpl shared reducer fixtures", () => {
  for (const fixtureCase of fixtures.cases) {
    it(fixtureCase.name, () => {
      const items = eventsToItemsImpl(
        fixtureCase.events as StoredEvent[],
        fixtures.mission as Mission,
      );
      expectItemsContain(items, fixtureCase.expected as ExpectedItem[]);

      if (fixtureCase.name === "duplicate event ids") {
        expect(items.filter((item) => item.kind === "assistant")).toHaveLength(
          1,
        );
      }
      if (fixtureCase.name === "goal deliverable inference") {
        expect(items.filter((item) => item.kind === "thinking")).toHaveLength(
          0,
        );
      }
    });
  }
});

describe("eventsToItemsImpl text_delta replay", () => {
  it("keeps a completed non-duplicate stream draft after an assistant reply", () => {
    const items = eventsToItemsImpl(
      [
        storedEvent(
          1,
          "text_delta",
          "I checked the failing run and found the artifact script path issue.",
        ),
        storedEvent(2, "assistant_message", "Fixed and pushed the branch.", undefined, {
          success: true,
        }),
      ],
      { status: "awaiting_user" } as Mission,
    );

    expect(items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "stream",
          content:
            "I checked the failing run and found the artifact script path issue.",
          done: true,
        }),
      ]),
    );
  });

  it("drops a stream draft that duplicates the final assistant reply", () => {
    const answer =
      "Fixed and pushed the branch after updating the artifact script path.";
    const items = eventsToItemsImpl(
      [
        storedEvent(1, "text_delta", answer),
        storedEvent(2, "assistant_message", answer, undefined, {
          success: true,
        }),
      ],
      { status: "awaiting_user" } as Mission,
    );

    expect(items.filter((item) => item.kind === "stream")).toHaveLength(0);
  });

  it("flushes narration emitted between tool calls instead of dropping it", () => {
    const items = eventsToItemsImpl(
      [
        storedEvent(1, "text_delta", "Now regenerating artifacts and running make check."),
        { ...storedEvent(2, "tool_call", '{"command":"make check"}'), tool_call_id: "t1", tool_name: "Bash" },
        { ...storedEvent(3, "tool_result", '{"content":"ok"}'), tool_call_id: "t1", tool_name: "Bash" },
        storedEvent(4, "text_delta", "All checks passed, opening the PR."),
        storedEvent(5, "assistant_message", "Done: PR opened.", undefined, {
          success: true,
        }),
      ],
      { status: "awaiting_user" } as Mission,
    );

    const streams = items.filter((item) => item.kind === "stream");
    expect(streams).toHaveLength(2);
    expect(streams[0]).toMatchObject({
      content: "Now regenerating artifacts and running make check.",
      done: true,
    });
    expect(streams[1]).toMatchObject({
      content: "All checks passed, opening the PR.",
      done: true,
    });
    // The intermediate narration must render before the tool call.
    const streamIdx = items.findIndex((item) => item.kind === "stream");
    const toolIdx = items.findIndex((item) => item.kind === "tool");
    expect(streamIdx).toBeLessThan(toolIdx);
  });

  it("removes a flushed narration that duplicates the later assistant reply", () => {
    const answer =
      "All checks passed and the branch is pushed; the PR is ready for review now.";
    const items = eventsToItemsImpl(
      [
        storedEvent(1, "text_delta", answer),
        { ...storedEvent(2, "tool_call", '{"command":"true"}'), tool_call_id: "t1", tool_name: "Bash" },
        { ...storedEvent(3, "tool_result", '{"content":"ok"}'), tool_call_id: "t1", tool_name: "Bash" },
        storedEvent(4, "assistant_message", answer, undefined, {
          success: true,
        }),
      ],
      { status: "awaiting_user" } as Mission,
    );

    expect(items.filter((item) => item.kind === "stream")).toHaveLength(0);
    expect(items.filter((item) => item.kind === "assistant")).toHaveLength(1);
  });
});

describe("eventsToItemsImpl thinking replay", () => {
  it("renders one item per block-final thinking event", () => {
    const items = eventsToItemsImpl(
      [
        storedEvent(1, "thinking", "first block", undefined, { done: true }),
        storedEvent(2, "thinking", "second block", undefined, { done: true }),
        storedEvent(3, "assistant_message", "done", undefined, {
          success: true,
        }),
      ],
      { status: "awaiting_user" } as Mission,
    );

    const thinking = items.filter((item) => item.kind === "thinking");
    expect(thinking).toHaveLength(2);
    expect(thinking[0]).toMatchObject({ content: "first block", done: true });
    expect(thinking[1]).toMatchObject({ content: "second block", done: true });
  });

  it("ignores legacy empty done finalizers with no open block", () => {
    const items = eventsToItemsImpl(
      [
        storedEvent(1, "thinking", "", undefined, { done: true }),
        storedEvent(2, "assistant_message", "done", undefined, {
          success: true,
        }),
      ],
      { status: "awaiting_user" } as Mission,
    );

    expect(items.filter((item) => item.kind === "thinking")).toHaveLength(0);
  });
});

describe("eventsToItemsImpl lazy tool stubs", () => {
  it("renders a tool_stub as a lazy tool row", () => {
    const items = eventsToItemsImpl([
      {
        ...storedEvent(1, "tool_stub", "", "2026-05-28T10:00:01Z", {
          lazy: true,
          has_result: true,
          result_timestamp: "2026-05-28T10:00:03Z",
          call_content_bytes: 15,
          result_content_bytes: 25,
        }),
        tool_call_id: "tool-1",
        tool_name: "bash",
      },
    ]);

    expect(items).toEqual([
      expect.objectContaining({
        kind: "tool",
        toolCallId: "tool-1",
        name: "bash",
        lazy: true,
        hasResult: true,
        contentBytes: 15,
        resultBytes: 25,
        endTime: new Date("2026-05-28T10:00:03Z").getTime(),
      }),
    ]);
  });

  it("hydrates a lazy tool row when a full tool_result is replayed", () => {
    const items = eventsToItemsImpl([
      {
        ...storedEvent(1, "tool_stub", "", "2026-05-28T10:00:01Z", {
          lazy: true,
          has_result: true,
        }),
        tool_call_id: "tool-1",
        tool_name: "bash",
      },
      {
        ...storedEvent(
          2,
          "tool_result",
          '{"success":false,"error":"boom"}',
          "2026-05-28T10:00:02Z",
        ),
        tool_call_id: "tool-1",
        tool_name: "bash",
      },
    ]);

    expect(items).toEqual([
      expect.objectContaining({
        kind: "tool",
        toolCallId: "tool-1",
        lazy: false,
        loading: false,
        hasResult: true,
        result: { success: false, error: "boom" },
      }),
    ]);
  });
});

describe("extractMissionState", () => {
  it("derives plan and up-next from harness tool items", async () => {
    const { extractMissionState } = await import("./events-reducer");
    const items = eventsToItemsImpl([
      {
        ...storedEvent(1, "tool_call", JSON.stringify({
          todos: [
            { content: "merge PR", status: "completed" },
            { content: "port Frames", status: "in_progress" },
          ],
        })),
        tool_call_id: "t1",
        tool_name: "TodoWrite",
      },
      {
        ...storedEvent(2, "tool_call", JSON.stringify({
          delaySeconds: 1500,
          reason: "review foundry shard 3 then merge",
          prompt: "Wakeup: ...",
        })),
        tool_call_id: "t2",
        tool_name: "ScheduleWakeup",
      },
    ]);
    const state = extractMissionState(items);
    expect(state.plan?.items).toEqual([
      { content: "merge PR", status: "completed" },
      { content: "port Frames", status: "in_progress" },
    ]);
    expect(state.upNext?.reason).toBe("review foundry shard 3 then merge");
    expect(state.upNext?.delaySeconds).toBe(1500);
  });
});
