import Foundation

// Models for the Ask assistant — the non-interrupting sidecar co-pilot.
// These mirror the backend `ask_threads` / `ask_messages` payloads exactly.
// (The decoder uses plain JSONDecoder with no key strategy, so every snake_case
// field needs an explicit CodingKey.)

// MARK: - Thread

struct AskThread: Codable, Identifiable {
    let id: String
    let missionId: String
    let title: String?
    let model: String?
    let createdAt: String
    let updatedAt: String

    enum CodingKeys: String, CodingKey {
        case id, title, model
        case missionId = "mission_id"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }

    var displayTitle: String { title ?? "Untitled thread" }
}

// MARK: - Message

struct AskMessage: Codable, Identifiable {
    let id: String
    let threadId: String
    let seq: Int
    /// "user" | "assistant" | "tool_call" | "tool_result"
    let role: String
    let content: String
    let toolName: String?
    let toolCallId: String?
    let createdAt: String
    /// Client-only delivery state for optimistic user bubbles. Excluded from
    /// `CodingKeys`, so it's never decoded from / encoded to the backend and
    /// defaults to `.sent` for server-sourced messages. Mirrors the main
    /// composer's `ChatMessage.sendState` so Ask gets the same
    /// pending/failed/tap-to-retry UX.
    var sendState: MessageSendState = .sent

    enum CodingKeys: String, CodingKey {
        case id, seq, role, content
        case threadId = "thread_id"
        case toolName = "tool_name"
        case toolCallId = "tool_call_id"
        case createdAt = "created_at"
        // `metadata` and `sendState` are intentionally omitted — arbitrary
        // JSON we don't render, and a client-only field, respectively.
    }

    var isUser: Bool { role == "user" }
    var isAssistant: Bool { role == "assistant" }
    var isToolCall: Bool { role == "tool_call" }
    var isToolResult: Bool { role == "tool_result" }
    var isTool: Bool { isToolCall || isToolResult }
}

// MARK: - API responses

/// Response of `POST /api/control/missions/:id/ask`.
struct AskSendResponse: Codable {
    let threadId: String
    let answer: String
    let messages: [AskMessage]

    enum CodingKeys: String, CodingKey {
        case answer, messages
        case threadId = "thread_id"
    }
}

/// Response of `GET /api/control/missions/:id/ask/threads/:tid`
/// (thread fields flattened alongside `messages`).
struct AskThreadDetail: Codable {
    let id: String
    let missionId: String
    let title: String?
    let model: String?
    let createdAt: String
    let updatedAt: String
    let messages: [AskMessage]

    enum CodingKeys: String, CodingKey {
        case id, title, model, messages
        case missionId = "mission_id"
        case createdAt = "created_at"
        case updatedAt = "updated_at"
    }
}

/// One Server-Sent Event from `POST /api/control/missions/:id/ask/stream`.
/// A single shape covers every variant (`type` discriminates).
struct AskStreamEvent: Decodable {
    let type: String
    let content: String?
    let toolCallId: String?
    let name: String?
    let args: String?
    let result: String?
    let threadId: String?
    let answer: String?
    let message: String?

    enum CodingKeys: String, CodingKey {
        case type, content, name, args, result, answer, message
        case toolCallId = "tool_call_id"
        case threadId = "thread_id"
    }
}

/// Error carrying a co-pilot stream failure message (from an SSE `error` event
/// or an abruptly-ended stream), so it surfaces through the normal throw path.
struct AskStreamError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}
