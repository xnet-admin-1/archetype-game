package com.xnet.archetype

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONObject

enum class SyncState { DISCONNECTED, HOSTING, JOINED }

object GameSync {
    private const val RELAY = "wss://ship.xnet.ngo/ws"
    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    var state = SyncState.DISCONNECTED; private set
    var roomCode = ""; private set

    private val _messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 16)
    val messages: SharedFlow<JSONObject> = _messages

    fun host(onCode: (String) -> Unit) {
        connect("$RELAY?action=host") { msg ->
            when (msg.optString("type")) {
                "room" -> { roomCode = msg.getString("code"); state = SyncState.HOSTING; onCode(roomCode) }
                "peer_joined" -> _messages.tryEmit(msg)
                else -> _messages.tryEmit(msg)
            }
        }
    }

    fun join(code: String, onJoined: () -> Unit) {
        connect("$RELAY?action=join&code=$code") { msg ->
            when (msg.optString("type")) {
                "joined" -> { roomCode = code; state = SyncState.JOINED; onJoined() }
                "error" -> { state = SyncState.DISCONNECTED }
                else -> _messages.tryEmit(msg)
            }
        }
    }

    fun send(type: String, data: JSONObject = JSONObject()) {
        data.put("type", type)
        ws?.send(data.toString())
    }

    fun sendTurn(role: String, text: String) {
        send("turn", JSONObject().put("role", role).put("text", text))
    }

    fun sendState(state: GameState) {
        // Send full state for sync
        send("sync", JSONObject().put("phase", state.phase.name)
            .put("turnCount", state.turnCount).put("currentRole", state.currentRole.name))
    }

    fun disconnect() {
        ws?.close(1000, "bye")
        ws = null
        state = SyncState.DISCONNECTED
        roomCode = ""
    }

    private fun connect(url: String, onMessage: (JSONObject) -> Unit) {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { onMessage(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                state = SyncState.DISCONNECTED
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                state = SyncState.DISCONNECTED
            }
        })
    }
}
