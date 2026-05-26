package com.constellation.glass.wss

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Minimal OkHttp-backed WSS client to Cortex. Holds one persistent socket;
 * reconnects with exponential back-off. Inbound JSON frames published via
 * [inbound] (a SharedFlow). Outbound via [send].
 *
 * Connection lifecycle state surfaced via [connected] — drives the OFFLINE
 * HUD overlay (Phase 3b.2).
 */
class WssClient(
    private val url: String,
    private val scope: CoroutineScope,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Keep TLS / TCP warm so audio_chunk RTT doesn't include handshake.
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _inbound = MutableSharedFlow<JsonObject>(replay = 0, extraBufferCapacity = 64)
    val inbound: SharedFlow<JsonObject> = _inbound.asSharedFlow()

    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    // ── public surface ─────────────────────────────────────────────────

    fun connect() {
        if (socket != null) return
        // The capabilities query-string tells Cortex which glass-shaped command
        // kinds we'll handle. Older clients (Console) don't pass this and get
        // the existing schema only.
        val capabilities = listOf("hud_state", "card", "insight", "mic_open", "mic_close")
            .joinToString(",")
        val urlWithCaps = if ("?" in url) "$url&accept=$capabilities" else "$url?accept=$capabilities"
        Timber.i("WssClient · connecting to $urlWithCaps")
        val req = Request.Builder().url(urlWithCaps).build()
        socket = client.newWebSocket(req, Listener())
    }

    fun disconnect() {
        Timber.i("WssClient · disconnecting")
        reconnectJob?.cancel()
        socket?.close(1000, "shutdown")
        socket = null
        _connected.value = false
    }

    /** Send an outbound event frame. No-op if socket isn't open (the state
     *  machine routes around OFFLINE; we don't queue here to avoid stale
     *  audio chunks arriving after the user moved on). */
    fun send(json: String): Boolean {
        val sock = socket ?: return false
        val ok = sock.send(json)
        if (!ok) Timber.w("WssClient · send dropped (buffer full or closed)")
        return ok
    }

    /** Send a [GlassEvent]. Uses kotlinx-serialization. */
    inline fun <reified T : GlassEvent> sendEvent(ev: T): Boolean {
        val s = json.encodeToString(kotlinx.serialization.serializer(), ev)
        return send(s)
    }

    // ── internal: reconnect + dispatch ─────────────────────────────────

    private inner class Listener : WebSocketListener() {
        private var attempt = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("WssClient · open (HTTP ${response.code})")
            attempt = 0
            _connected.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                val kind = obj["kind"]?.jsonPrimitive?.content ?: "?"
                Timber.v("WssClient · in: $kind")
                scope.launch { _inbound.emit(obj) }
            } catch (e: Throwable) {
                Timber.w(e, "WssClient · bad inbound frame")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Cortex doesn't send binary frames in this protocol; ignore but log.
            Timber.w("WssClient · unexpected binary frame (${bytes.size} bytes)")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("WssClient · closing $code · $reason")
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Timber.i("WssClient · closed $code · $reason")
            _connected.value = false
            socket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Timber.w(t, "WssClient · failure (HTTP ${response?.code})")
            _connected.value = false
            socket = null
            scheduleReconnect()
        }

        private fun scheduleReconnect() {
            reconnectJob?.cancel()
            attempt++
            val delayMs = backoffMs(attempt)
            Timber.i("WssClient · reconnect in ${delayMs}ms (attempt $attempt)")
            reconnectJob = scope.launch(Dispatchers.IO) {
                delay(delayMs)
                connect()
            }
        }
    }

    private fun backoffMs(attempt: Int): Long {
        // 1s, 2s, 4s, … capped at 30s. Plus jitter to avoid thundering herd.
        val base = (1L shl minOf(attempt - 1, 5)) * 1_000L
        val jitter = (Math.random() * 500).toLong()
        return base.coerceAtMost(30_000L) + jitter
    }
}
