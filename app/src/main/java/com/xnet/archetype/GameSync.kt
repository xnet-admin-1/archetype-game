package com.xnet.archetype

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class SyncState { DISCONNECTED, HOSTING, JOINED, IN_LOBBY }

data class LobbyMessage(val sender: String, val text: String, val timestamp: Long = System.currentTimeMillis())

object GameSync {
    private const val RELAY = "wss://ship.xnet.ngo/ws"
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    private var lastUrl: String? = null
    private var lastHandler: ((JSONObject) -> Unit)? = null

    var state = SyncState.DISCONNECTED; private set
    var roomCode = ""; private set
    var playerName = ""; private set
    var connectedPlayers = mutableListOf<String>(); private set

    private val _messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 32)
    val messages: SharedFlow<JSONObject> = _messages

    private val _lobbyChat = MutableSharedFlow<LobbyMessage>(extraBufferCapacity = 64)
    val lobbyChat: SharedFlow<LobbyMessage> = _lobbyChat

    val chatHistory = mutableListOf<LobbyMessage>()

    fun host(name: String, onCode: (String) -> Unit, onError: (String) -> Unit = {}) {
        playerName = name
        val url = "$RELAY?action=host&name=${java.net.URLEncoder.encode(name, "UTF-8")}"
        var gotResponse = false
        connect(url, onConnectFail = {
            if (!gotResponse) {
                // Fallback: generate local room code
                roomCode = (1000..9999).random().toString()
                state = SyncState.HOSTING
                connectedPlayers.clear()
                connectedPlayers.add(name)
                onCode(roomCode)
            }
        }) { msg ->
            gotResponse = true
            when (msg.optString("type")) {
                "room" -> {
                    roomCode = msg.getString("code")
                    state = SyncState.HOSTING
                    connectedPlayers.clear()
                    connectedPlayers.add(name)
                    onCode(roomCode)
                }
                "peer_joined" -> {
                    val peer = msg.optString("name", "Player")
                    if (peer !in connectedPlayers) connectedPlayers.add(peer)
                    _messages.tryEmit(msg)
                }
                "peer_left" -> {
                    connectedPlayers.remove(msg.optString("name", ""))
                    _messages.tryEmit(msg)
                }
                "chat" -> {
                    val lm = LobbyMessage(msg.getString("sender"), msg.getString("text"))
                    chatHistory.add(lm)
                    _lobbyChat.tryEmit(lm)
                }
                else -> _messages.tryEmit(msg)
            }
        }
    }

    fun join(code: String, name: String, onJoined: () -> Unit, onError: (String) -> Unit = {}) {
        playerName = name
        val url = "$RELAY?action=join&code=$code&name=${java.net.URLEncoder.encode(name, "UTF-8")}"
        connect(url, onConnectFail = { onError("Could not connect to server") }) { msg ->
            when (msg.optString("type")) {
                "joined" -> {
                    roomCode = code
                    state = SyncState.JOINED
                    connectedPlayers.clear()
                    msg.optJSONArray("players")?.let { arr ->
                        for (i in 0 until arr.length()) connectedPlayers.add(arr.getString(i))
                    }
                    if (name !in connectedPlayers) connectedPlayers.add(name)
                    onJoined()
                }
                "peer_joined" -> {
                    val peer = msg.optString("name", "Player")
                    if (peer !in connectedPlayers) connectedPlayers.add(peer)
                    _messages.tryEmit(msg)
                }
                "peer_left" -> {
                    connectedPlayers.remove(msg.optString("name", ""))
                    _messages.tryEmit(msg)
                }
                "chat" -> {
                    val lm = LobbyMessage(msg.getString("sender"), msg.getString("text"))
                    chatHistory.add(lm)
                    _lobbyChat.tryEmit(lm)
                }
                "error" -> { state = SyncState.DISCONNECTED }
                else -> _messages.tryEmit(msg)
            }
        }
    }

    fun sendChat(text: String) {
        val msg = JSONObject().put("type", "chat").put("sender", playerName).put("text", text)
        ws?.send(msg.toString())
        val lm = LobbyMessage(playerName, text)
        chatHistory.add(lm)
        _lobbyChat.tryEmit(lm)
    }

    fun send(type: String, data: JSONObject = JSONObject()) {
        data.put("type", type)
        ws?.send(data.toString())
    }

    fun sendTurn(role: String, text: String) {
        send("turn", JSONObject().put("role", role).put("text", text))
    }

    fun sendState(state: GameState) {
        send("sync", JSONObject().put("phase", state.phase.name)
            .put("turnCount", state.turnCount).put("currentRole", state.currentRole.name))
    }

    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        ws?.close(1000, "bye")
        ws = null
        state = SyncState.DISCONNECTED
        roomCode = ""
        connectedPlayers.clear()
        chatHistory.clear()
        lastUrl = null
        lastHandler = null
    }

    private fun connect(url: String, onConnectFail: () -> Unit = {}, onMessage: (JSONObject) -> Unit) {
        lastUrl = url
        lastHandler = onMessage
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {}
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { onMessage(JSONObject(text)) } catch (_: Exception) {}
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onConnectFail()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (state != SyncState.DISCONNECTED) attemptReconnect()
            }
        })
    }

    private fun attemptReconnect() {
        reconnectJob?.cancel()
        reconnectJob = CoroutineScope(Dispatchers.IO).launch {
            delay(2000)
            lastUrl?.let { url -> lastHandler?.let { handler -> connect(url, onMessage = handler) } }
        }
    }
}
