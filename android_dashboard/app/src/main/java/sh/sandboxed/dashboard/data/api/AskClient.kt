package sh.sandboxed.dashboard.data.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import sh.sandboxed.dashboard.data.AskStreamEvent
import sh.sandboxed.dashboard.data.AskStreamException
import sh.sandboxed.dashboard.data.AskStreamRequest
import java.util.concurrent.atomic.AtomicBoolean

/// Streaming transport for the Ask co-pilot. Mirrors [SseClient] but issues a
/// POST with a JSON body to `/api/control/missions/{id}/ask/stream` and parses
/// each `data:` line into an [AskStreamEvent].
///
/// Terminal semantics match the iOS client: a stream `error` event and a body
/// that ends without a `done` event both surface as an [AskStreamException]
/// through the Flow's error channel, so the caller can roll back the turn.
class AskClient(
    private val api: ApiService,
    private val streamingClient: OkHttpClient,
) {
    private val jsonMedia = "application/json".toMediaType()

    fun stream(missionId: String, content: String, threadId: String?): Flow<AskStreamEvent> = callbackFlow {
        val url = api.urlOf("/api/control/missions/$missionId/ask/stream")
        val payload = Net.json.encodeToString(
            AskStreamRequest.serializer(),
            AskStreamRequest(content = content, threadId = threadId),
        )
        val req: Request = api.newRequestBuilder(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .post(payload.toRequestBody(jsonMedia))
            .build()

        val sawTerminal = AtomicBoolean(false)
        val factory = EventSources.createFactory(streamingClient)
        val source: EventSource = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val ev = runCatching { Net.json.decodeFromString(AskStreamEvent.serializer(), data) }.getOrNull()
                    ?: return
                when (ev.type) {
                    // Surface a stream `error` event through the error path so the
                    // consumer handles it like any other failure (rollback + retry).
                    "error" -> {
                        sawTerminal.set(true)
                        close(AskStreamException(ev.message ?: "Ask failed"))
                    }
                    "done" -> {
                        sawTerminal.set(true)
                        trySend(ev)
                        close()
                    }
                    else -> trySend(ev)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                // A dropped/truncated body that never delivered `done` is a
                // failure, not a silent success.
                if (sawTerminal.get()) close()
                else close(AskStreamException("Stream ended before completion"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (sawTerminal.get()) {
                    close()
                } else {
                    close(t ?: AskStreamException("Ask stream failed: ${response?.code}"))
                }
            }
        })
        awaitClose { source.cancel() }
    }
}
