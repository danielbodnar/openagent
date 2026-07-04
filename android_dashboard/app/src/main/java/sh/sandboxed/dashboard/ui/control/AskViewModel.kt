package sh.sandboxed.dashboard.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import sh.sandboxed.dashboard.data.AppContainer
import sh.sandboxed.dashboard.data.AskMessage
import sh.sandboxed.dashboard.data.AskSendState
import sh.sandboxed.dashboard.data.AskStreamEvent
import sh.sandboxed.dashboard.data.AskThread
import java.util.UUID

data class AskState(
    val threads: List<AskThread> = emptyList(),
    val threadId: String? = null,
    val messages: List<AskMessage> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/// Drives one Ask co-pilot conversation for a single mission. Mirrors the iOS
/// AskSheet logic: optimistic user bubbles are **preserved** across failures
/// (flipped to failed + tap-to-retry) while only this turn's streamed
/// assistant/tool bubbles roll back. A monotonically increasing [gen] counter
/// drops results from superseded turns / thread switches.
class AskViewModel(
    private val container: AppContainer,
    private val missionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state.asStateFlow()

    private var streamJob: Job? = null
    // Id of the assistant bubble currently being streamed into (null between segments).
    private var streamId: String? = null
    // Bumped on send / thread switch / clear so a stale post-stream sync is skipped.
    private var gen = 0

    init { loadThreads() }

    /// Cancel any in-flight stream. Called from the sheet's onDispose.
    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun loadThreads() {
        viewModelScope.launch {
            runCatching { container.api.listAskThreads(missionId) }
                .onSuccess { fetched ->
                    _state.update { it.copy(threads = fetched) }
                    fetched.firstOrNull()?.let { selectThread(it.id) }
                }
            // Non-fatal — an empty thread is a valid starting point.
        }
    }

    fun selectThread(id: String) {
        gen += 1
        val g = gen
        streamJob?.cancel()
        streamId = null
        _state.update { it.copy(threadId = id, isLoading = false) }
        viewModelScope.launch {
            runCatching { container.api.getAskThread(missionId, id) }
                .onSuccess { detail -> if (g == gen) _state.update { it.copy(messages = detail.messages) } }
                .onFailure { if (g == gen) _state.update { it.copy(messages = emptyList()) } }
        }
    }

    fun newThread() {
        gen += 1
        streamJob?.cancel()
        streamId = null
        _state.update { it.copy(threadId = null, messages = emptyList(), isLoading = false, error = null) }
    }

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _state.value.isLoading) return
        val userId = "u-${UUID.randomUUID()}"
        val msg = AskMessage(
            id = userId,
            threadId = _state.value.threadId ?: "",
            seq = _state.value.messages.size + 1,
            role = "user",
            content = content,
            sendState = AskSendState.Pending,
        )
        _state.update { it.copy(messages = it.messages + msg) }
        runTurn(userId, content)
    }

    /// Re-run a co-pilot turn for a user message whose send failed. Reuses the
    /// existing bubble (no duplicate) with the same rollback semantics.
    fun retry(message: AskMessage) {
        if (!message.isUser || !message.sendState.isFailed || _state.value.isLoading) return
        runTurn(message.id, message.content)
    }

    fun clearThread() {
        val id = _state.value.threadId ?: return
        gen += 1
        streamJob?.cancel()
        streamId = null
        viewModelScope.launch {
            runCatching { container.api.deleteAskThread(missionId, id) }
            val fresh = runCatching { container.api.listAskThreads(missionId) }.getOrNull()
            _state.update {
                it.copy(
                    threads = fresh ?: it.threads,
                    threadId = null,
                    messages = emptyList(),
                    isLoading = false,
                    error = null,
                )
            }
        }
    }

    private fun runTurn(userMessageId: String, content: String) {
        gen += 1
        val g = gen
        streamJob?.cancel()
        streamId = null
        updateMessage(userMessageId) { it.copy(sendState = AskSendState.Pending) }
        _state.update { it.copy(isLoading = true, error = null) }
        // Roll-back boundary: keep everything up to and including the user
        // bubble; drop streamed bubbles appended during this turn on failure.
        val baseCount = _state.value.messages.indexOfFirst { it.id == userMessageId }
            .let { if (it >= 0) it + 1 else _state.value.messages.size }
        val threadId = _state.value.threadId

        streamJob = viewModelScope.launch {
            var failure: String? = null
            var firstEvent = true
            try {
                container.ask.stream(missionId, content, threadId).collect { ev ->
                    if (g != gen) return@collect
                    // First event back means the backend accepted the message.
                    if (firstEvent) {
                        firstEvent = false
                        updateMessage(userMessageId) {
                            if (it.sendState.isPending) it.copy(sendState = AskSendState.Sent) else it
                        }
                    }
                    handleStreamEvent(ev, g)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Throwable) {
                failure = e.message ?: "Ask failed"
            }

            // Superseded — a newer turn owns the list and the loading flag now.
            if (g != gen) return@launch

            if (failure != null) {
                _state.update { st ->
                    val trimmed = if (st.messages.size > baseCount) st.messages.take(baseCount) else st.messages
                    st.copy(
                        messages = trimmed.map {
                            if (it.id == userMessageId) it.copy(sendState = AskSendState.Failed(failure!!)) else it
                        },
                    )
                }
            } else {
                updateMessage(userMessageId) { it.copy(sendState = AskSendState.Sent) }
            }
            _state.update { it.copy(isLoading = false) }
            streamId = null
        }
    }

    private fun handleStreamEvent(ev: AskStreamEvent, g: Int) {
        when (ev.type) {
            "delta" -> {
                val textPart = ev.content ?: return
                val sid = streamId
                if (sid != null) {
                    updateMessage(sid) { it.copy(content = it.content + textPart) }
                } else {
                    val id = "a-${UUID.randomUUID()}"
                    streamId = id
                    _state.update {
                        it.copy(
                            messages = it.messages + AskMessage(
                                id = id,
                                threadId = it.threadId ?: "",
                                seq = it.messages.size + 1,
                                role = "assistant",
                                content = textPart,
                            ),
                        )
                    }
                }
            }
            "tool_call" -> {
                streamId = null
                _state.update {
                    it.copy(
                        messages = it.messages + AskMessage(
                            id = "tc-${ev.toolCallId ?: UUID.randomUUID()}",
                            threadId = it.threadId ?: "",
                            seq = it.messages.size + 1,
                            role = "tool_call",
                            content = ev.args ?: "",
                            toolName = ev.name,
                            toolCallId = ev.toolCallId,
                        ),
                    )
                }
            }
            "tool_result" -> {
                _state.update {
                    it.copy(
                        messages = it.messages + AskMessage(
                            id = "tr-${ev.toolCallId ?: UUID.randomUUID()}",
                            threadId = it.threadId ?: "",
                            seq = it.messages.size + 1,
                            role = "tool_result",
                            content = ev.result ?: "",
                            toolName = ev.name,
                            toolCallId = ev.toolCallId,
                        ),
                    )
                }
            }
            "done" -> {
                streamId = null
                ev.threadId?.let { tid -> _state.update { it.copy(threadId = tid) } }
                val tid = ev.threadId ?: _state.value.threadId
                // Reconcile the streamed bubbles with the canonical persisted
                // messages, unless a newer send / thread switch superseded this.
                viewModelScope.launch {
                    runCatching { container.api.listAskThreads(missionId) }
                        .onSuccess { fetched -> if (g == gen) _state.update { it.copy(threads = fetched) } }
                    if (tid != null) {
                        runCatching { container.api.getAskThread(missionId, tid) }
                            .onSuccess { detail ->
                                if (g == gen && _state.value.threadId == tid) {
                                    _state.update { it.copy(messages = detail.messages) }
                                }
                            }
                    }
                }
            }
            // "error" is surfaced via the Flow error path in AskClient and
            // handled in runTurn's catch (gen-guarded rollback + restore).
        }
    }

    private fun updateMessage(id: String, transform: (AskMessage) -> AskMessage) {
        _state.update { st -> st.copy(messages = st.messages.map { if (it.id == id) transform(it) else it }) }
    }
}
