package sh.sandboxed.dashboard.data.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import sh.sandboxed.dashboard.data.SseEvent

/// WebSocket fallback for the control event stream (`/api/control/ws`), used
/// when the primary SSE transport keeps failing (some proxies buffer or drop
/// long-lived SSE responses). Frames are AgentEvent JSON objects tagged with a
/// `type` field; heartbeat frames carry only `{"seq": N}` and are skipped.
class ControlWsClient(private val api: ApiService, private val client: OkHttpClient) {

    fun stream(): Flow<SseEvent> = callbackFlow {
        val req = api.newRequestBuilder(api.urlOf("/api/control/ws")).build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { Net.json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
                val type = obj["type"]?.jsonPrimitive?.content ?: return
                trySend(SseEvent(type, obj))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                close(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        awaitClose { ws.cancel() }
    }
}
