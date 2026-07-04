package sh.sandboxed.dashboard.data.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import sh.sandboxed.dashboard.data.AppSettings
import java.util.concurrent.atomic.AtomicLong

sealed class TerminalEvent {
    data object Connected : TerminalEvent()
    data class Output(val text: String) : TerminalEvent()
    data class Closed(val reason: String?) : TerminalEvent()
    data class Failure(val error: Throwable) : TerminalEvent()
}

@Serializable
private data class WsInput(@SerialName("t") val t: String, @SerialName("d") val d: String)

@Serializable
private data class WsResize(@SerialName("t") val t: String, @SerialName("c") val c: Int, @SerialName("r") val r: Int)

private data class ActiveTerminalSocket(val connectionId: Long, val webSocket: WebSocket)

class TerminalSocket(
    private val client: OkHttpClient,
    private val provider: () -> AppSettings,
) {
    @Volatile private var activeSocket: ActiveTerminalSocket? = null
    @Volatile private var activeConnectionId: Long = 0
    private val nextConnectionId = AtomicLong(0)

    fun connect(workspaceId: String? = null): Flow<TerminalEvent> = callbackFlow {
        val connectionId = nextConnectionId.incrementAndGet()
        activeConnectionId = connectionId
        val previousSocket = activeSocket?.webSocket
        activeSocket = null
        previousSocket?.close(1000, "replaced by new terminal connection")

        val s = provider()
        val httpUrl = s.baseUrl.trimEnd('/').ifBlank { error("Server URL not configured") }
        val wsUrl = httpUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val path = if (workspaceId.isNullOrBlank()) "/api/console/ws" else "/api/workspaces/$workspaceId/shell"
        val protocols = listOfNotNull(
            "sandboxed",
            s.jwtToken?.let { "jwt.$it" }
        ).joinToString(", ")
        val req = Request.Builder()
            .url("$wsUrl$path")
            .apply { if (protocols.isNotEmpty()) header("Sec-WebSocket-Protocol", protocols) }
            .build()

        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (activeConnectionId != connectionId) {
                    webSocket.close(1000, "stale terminal connection")
                    return
                }
                this@TerminalSocket.activeSocket = ActiveTerminalSocket(connectionId, webSocket)
                trySend(TerminalEvent.Connected)
                webSocket.send(Json.encodeToString(WsResize.serializer(), WsResize(t = "r", c = 80, r = 24)))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (activeConnectionId == connectionId) {
                    trySend(TerminalEvent.Output(text))
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (activeConnectionId == connectionId) {
                    trySend(TerminalEvent.Output(bytes.utf8()))
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (activeConnectionId == connectionId) {
                    this@TerminalSocket.activeSocket = null
                    trySend(TerminalEvent.Closed(reason))
                    close()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (activeConnectionId == connectionId) {
                    this@TerminalSocket.activeSocket = null
                    trySend(TerminalEvent.Failure(t))
                    close(t)
                }
            }
        })

        awaitClose {
            socket.close(1000, "client closing")
            if (activeConnectionId == connectionId) {
                this@TerminalSocket.activeSocket = null
            }
        }
    }

    fun sendInput(text: String): Boolean =
        activeWebSocket()?.send(Json.encodeToString(WsInput.serializer(), WsInput(t = "i", d = text))) == true

    fun sendResize(cols: Int, rows: Int) {
        activeWebSocket()?.send(Json.encodeToString(WsResize.serializer(), WsResize(t = "r", c = cols, r = rows)))
    }

    private fun activeWebSocket(): WebSocket? =
        activeSocket
            ?.takeIf { it.connectionId == activeConnectionId }
            ?.webSocket
}
