package com.constellation.glass.wss

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import okio.ByteString.Companion.toByteString
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
    /** Returns the current "Cookie:" header value to attach to upgrade, or
     *  null if no auth cookie is available. ConstellationService supplies a
     *  reference to [com.constellation.glass.auth.CookieStore.read]. */
    private val cookieProvider: () -> String? = { null },
    /** Called when the upgrade response is 401 — the existing cookie is no
     *  longer valid and the user must re-login. */
    private val onUnauthorized: () -> Unit = {},
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        // Keep TLS / TCP warm so audio_chunk RTT doesn't include handshake.
        // ping every 10s (was 15s): keeps the WiFi radio active enough to
        // avoid YodaOS's Wake-on-Wireless (WoW) deep-sleep that was breaking
        // subsequent TLS handshakes on Rokid Glasses.
        .pingInterval(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // TLS handshake under WoW-paused WiFi can take a while to recover —
        // OkHttp default 10s connectTimeout was firing silently. Give the
        // handshake more headroom; logs will surface a real failure if it
        // still can't get through.
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null

    // Liveness preflight (Zack 2026-05-31): BT-PAN idle-drops the WSS silently —
    // the socket looks alive but frames vanish. Before a wake we ping→pong end
    // to end (cortex echoes); on timeout we reconnect. lastInboundMs lets a
    // recently-active connection skip the ping (fast wake).
    @Volatile private var lastInboundMs: Long = 0L
    @Volatile private var pendingPing: CompletableDeferred<Boolean>? = null
    private var heartbeatJob: Job? = null
    private val heartbeatIntervalMs = 60_000L
    private val activeWindowMs = 5 * 60_000L  // keep alive 5 min past the last real frame

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** True once the Edge rejected the upgrade with 401/403 (stale cookie) and
     *  we halted reconnect. Drives the AuthExpired HUD (re-scan to re-pair),
     *  distinct from a plain network drop. Reset on the next successful open. */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired.asStateFlow()

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
        // Unpaired (no endpoint scanned yet): a blank/invalid URL would crash
        // OkHttp's .url(). Treat it as "needs pairing" — same surface as an
        // expired cookie → the AuthExpired HUD prompts a scan. Don't attempt.
        if (!(url.startsWith("wss://") || url.startsWith("ws://"))) {
            Timber.w("WssClient · no endpoint configured — needs pairing")
            _connected.value = false
            _authExpired.value = true
            return
        }
        // Flush the connection pool before each new attempt. After Rokid Glasses
        // WiFi enters Wake-on-Wireless deep-sleep, sockets in the pool may be
        // "half-dead" — TCP-alive to OkHttp but actually unable to complete a
        // fresh TLS handshake. evictAll() forces a clean reconnect path.
        client.connectionPool.evictAll()
        // The capabilities query-string tells Cortex which glass-shaped command
        // kinds we'll handle. Older clients (Console) don't pass this and get
        // the existing schema only.
        //
        // R-13 / C-55: `request_image` added so Cortex's `_emit_glass_frame`
        // gate lets server-pull-on-demand vision frames through. Glass
        // responds with an `image_attached` event captured by CameraGate.
        val capabilities = listOf(
            "hud_state", "card", "insight", "mic_open", "mic_close",
            "request_image",
            // Voice-driven shortcut-slot config (Cortex parses "set shortcut N
            // to …" and emits this frame; we update the local slot store).
            "shortcut_config",
        ).joinToString(",")
        val urlWithCaps = if ("?" in url) "$url&accept=$capabilities" else "$url?accept=$capabilities"
        Timber.i("WssClient · connecting to $urlWithCaps")
        val reqBuilder = Request.Builder().url(urlWithCaps)
        cookieProvider()?.let { cookieHeader ->
            reqBuilder.header("Cookie", cookieHeader)
            Timber.v("WssClient · attaching auth cookie (${cookieHeader.length} chars)")
        }
        socket = client.newWebSocket(reqBuilder.build(), Listener())
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

    /** Send a raw binary frame — used for the photo upload (paired with a
     *  preceding `image_attached` header event). Avoids base64's +33% and a
     *  multi-MB JSON string. No-op if the socket isn't open. */
    fun sendBytes(bytes: ByteArray): Boolean {
        val sock = socket ?: return false
        val ok = sock.send(bytes.toByteString(0, bytes.size))
        if (!ok) Timber.w("WssClient · sendBytes dropped (buffer full or closed)")
        return ok
    }

    /**
     * Liveness preflight before a wake (Zack 2026-05-31): confirm the WSS path
     * is end-to-end live (ping → cortex → pong) so the audio frames don't vanish
     * into a BT-PAN-dropped socket. Fast when recently active; otherwise pings,
     * and reconnects if the pong times out. Returns whether the path is live.
     */
    suspend fun confirmAlive(timeoutMs: Long = 2_000L): Boolean {
        // Recently active → the path is fresh; skip the round-trip.
        if (System.currentTimeMillis() - lastInboundMs < 30_000L) return true
        if (socket != null && pingOnce(timeoutMs)) return true
        Timber.w("WssClient · preflight stale → reconnecting")
        forceReconnect()
        if (!awaitConnected(4_000L)) return false
        return pingOnce(timeoutMs)
    }

    private suspend fun pingOnce(timeoutMs: Long): Boolean {
        if (pendingPing != null) return true   // a ping is already in flight — don't race
        val sock = socket ?: return false
        val def = CompletableDeferred<Boolean>()
        pendingPing = def
        if (!sock.send("{\"kind\":\"ping\"}")) {
            pendingPing = null
            return false
        }
        return try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: TimeoutCancellationException) {
            false
        } finally {
            pendingPing = null
        }
    }

    private fun forceReconnect() {
        socket?.cancel()
        socket = null
        _connected.value = false
        client.connectionPool.evictAll()
        connect()
    }

    private suspend fun awaitConnected(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.first { it } } != null

    /**
     * Bounded keepalive (Zack 2026-05-31): within [activeWindowMs] of the last
     * REAL inbound frame, ping every [heartbeatIntervalMs] to keep the BT-PAN
     * connection alive AND catch a silent drop early (reconnect on miss). Goes
     * quiet past the window — no permanent idle heartbeat, so deep idle costs no
     * radio; the next wake's preflight recovers a connection that died while
     * quiet. Idempotent; starts one loop for the client's lifetime.
     */
    fun startHeartbeat() {
        if (heartbeatJob != null) return
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(heartbeatIntervalMs)
                val activeRecently = System.currentTimeMillis() - lastInboundMs < activeWindowMs
                if (activeRecently && socket != null && !pingOnce(2_000L)) {
                    Timber.w("WssClient · heartbeat lost — reconnecting")
                    forceReconnect()
                    awaitConnected(4_000L)
                }
            }
        }
    }

    // ── internal: reconnect + dispatch ─────────────────────────────────

    private inner class Listener : WebSocketListener() {
        private var attempt = 0

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Timber.i("WssClient · open (HTTP ${response.code})")
            attempt = 0
            _connected.value = true
            _authExpired.value = false   // fresh auth succeeded; clear the gate
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val obj = json.parseToJsonElement(text).jsonObject
                val kind = obj["kind"]?.jsonPrimitive?.content ?: "?"
                // Liveness pong → resolve the pending preflight; don't forward it
                // to the StateMachine (protocol frame, not a HUD command) and DON'T
                // count it as activity (else heartbeats self-extend the window).
                if (kind == "pong") {
                    pendingPing?.complete(true)
                    return
                }
                lastInboundMs = System.currentTimeMillis()   // a real inbound frame
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
            // INFO not warn so it surfaces under -s WssClient filter; the
            // class+message is the diagnostic key we need to see when
            // reconnects start failing silently under WoW.
            Timber.i("WssClient · failure (HTTP ${response?.code}) ${t.javaClass.simpleName}: ${t.message}")
            _connected.value = false
            socket = null
            // 401 = cookie present but invalid. 403 = edge restarted (in-memory
            // SessionStore wiped) and ws.close() before ws.accept() surfaces as
            // 403 during the WS upgrade. Both mean "re-login required" — clear
            // the cookie + prompt the user; don't keep reconnecting.
            if (response?.code == 401 || response?.code == 403) {
                Timber.w("WssClient · ${response.code} — stale cookie; halting reconnect")
                _authExpired.value = true   // drives the AuthExpired HUD (re-pair)
                onUnauthorized()
                return
            }
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
