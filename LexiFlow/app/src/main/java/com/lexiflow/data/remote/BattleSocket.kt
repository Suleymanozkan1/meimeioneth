package com.lexiflow.data.remote

import android.util.Log
import com.lexiflow.core.ai.LexiJson
import com.lexiflow.core.competition.ClientMessage
import com.lexiflow.core.competition.ServerMessage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class BattleSocketTarget(val url: String, val token: String) {
    override fun toString(): String = "BattleSocketTarget(url=$url, token=<redacted>)"
}

sealed class SocketEvent {
    data object Open : SocketEvent()
    data class Message(val message: ServerMessage) : SocketEvent()
    data class Closed(val reason: String) : SocketEvent()
    data class Failure(val messageTr: String) : SocketEvent()
}

/** OkHttp WebSocket client for the online word battle (protocol in core/competition). */
class BattleSocket(private val http: OkHttpClient = BackendClient.defaultClient()) {
    private var socket: WebSocket? = null

    fun connect(target: BattleSocketTarget): Flow<SocketEvent> = callbackFlow {
        val request = Request.Builder().url(target.url).header("Authorization", "Bearer ${target.token}").build()
        val ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { trySend(SocketEvent.Open) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { LexiJson.decodeFromString(ServerMessage.serializer(), text) }.getOrNull()
                if (msg != null) trySend(SocketEvent.Message(msg)) else Log.w("BattleSocket", "unknown message")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { trySend(SocketEvent.Closed(reason)); close() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = if (response?.code == 401) "Oturum geçersiz; Ayarlar'dan yeniden bağlan." else "Bağlantı koptu (${t.javaClass.simpleName})."
                trySend(SocketEvent.Failure(msg)); close()
            }
        })
        socket = ws
        awaitClose { ws.close(1000, "client closed") }
    }

    fun send(message: ClientMessage): Boolean = socket?.send(LexiJson.encodeToString(ClientMessage.serializer(), message)) ?: false

    fun close() { socket?.close(1000, "bye"); socket = null }
}
