package sh.sandboxed.dashboard.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

// Models for the Ask assistant — the non-interrupting sidecar co-pilot.
// These mirror the backend `ask_threads` / `ask_messages` payloads (and the
// iOS Ask.swift wire contract) exactly. Net.json uses snake_case field names,
// so every multi-word field needs an explicit @SerialName.

// ---- Client-only delivery state for optimistic user bubbles ----

/// Mirrors the main composer's send state so Ask gets the same
/// pending / failed / tap-to-retry UX. Never serialized.
sealed class AskSendState {
    data object Sent : AskSendState()
    data object Pending : AskSendState()
    data class Failed(val reason: String) : AskSendState()

    val isPending: Boolean get() = this is Pending
    val isFailed: Boolean get() = this is Failed
}

// ---- Thread ----

@Serializable
data class AskThread(
    val id: String,
    @SerialName("mission_id") val missionId: String = "",
    val title: String? = null,
    val model: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    val displayTitle: String get() = title?.takeIf { it.isNotBlank() } ?: "Untitled thread"
}

// ---- Message ----

@Serializable
data class AskMessage(
    val id: String,
    @SerialName("thread_id") val threadId: String = "",
    val seq: Int = 0,
    /// "user" | "assistant" | "tool_call" | "tool_result"
    val role: String,
    val content: String = "",
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    // Client-only — defaults to Sent for server-sourced messages.
    @Transient val sendState: AskSendState = AskSendState.Sent,
) {
    val isUser: Boolean get() = role == "user"
    val isAssistant: Boolean get() = role == "assistant"
    val isToolCall: Boolean get() = role == "tool_call"
    val isToolResult: Boolean get() = role == "tool_result"
    val isTool: Boolean get() = isToolCall || isToolResult
}

// ---- API payloads ----

/// Request body for `POST /api/control/missions/:id/ask[/stream]`.
@Serializable
data class AskStreamRequest(
    val content: String,
    @SerialName("thread_id") val threadId: String? = null,
)

/// Response of `GET /api/control/missions/:id/ask/threads/:tid`
/// (thread fields flattened alongside `messages`).
@Serializable
data class AskThreadDetail(
    val id: String,
    @SerialName("mission_id") val missionId: String = "",
    val title: String? = null,
    val model: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val messages: List<AskMessage> = emptyList(),
)

/// One Server-Sent Event from `POST /api/control/missions/:id/ask/stream`.
/// A single shape covers every variant (`type` discriminates).
@Serializable
data class AskStreamEvent(
    val type: String,
    val content: String? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    val args: String? = null,
    val result: String? = null,
    @SerialName("thread_id") val threadId: String? = null,
    val answer: String? = null,
    val message: String? = null,
)

/// Carries a co-pilot stream failure (an SSE `error` event or an abruptly
/// ended stream) so it surfaces through the normal Flow error path.
class AskStreamException(message: String) : RuntimeException(message)
