package com.constellation.glass

import com.constellation.glass.app.ui.CortexStatus
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Plain blocking HTTP client for the in-app settings status block. Calls
 * `GET <edge_base>/api/health` and maps the response shape (see Cortex
 * `cortex/http.py health()`) into a [CortexStatus].
 *
 * Why a separate small client instead of riding on the existing WssClient:
 *   - The WSS connection is owned by [ConstellationService] and not
 *     trivially queryable from the in-app UI without IPC.
 *   - `/api/health` is a cheap idempotent GET — no auth needed for the
 *     health probe in the current Cortex setup.
 *
 * Endpoint derivation: WSS URL `wss://host/ws/glass` → HTTP base `https://host`.
 * We swap `wss://`→`https://` and strip path. P1.6 used the same logic in
 * `CortexAuth.edgeBaseUrl()`; replicated here to avoid a circular dep through
 * the auth module.
 */
object CortexHealthClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun fetch(wssEndpoint: String): CortexStatus {
        val baseUrl = wssToHttpBase(wssEndpoint) ?: return CortexStatus()
        return try {
            val req = Request.Builder().url("$baseUrl/api/health").get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("health · HTTP ${resp.code}")
                    return CortexStatus()
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val stats = json.optJSONObject("stats")
                val serverBound = json.optBoolean("server_bound", false)
                val toolConn = json.optBoolean("tool_conn", false)
                val dispatches = stats?.optInt("dispatches_total", 0) ?: 0
                CortexStatus(
                    connected = serverBound && toolConn,
                    endpoint = wssEndpoint,
                    invokesTotal = dispatches,
                    lastInvokeAgo = "—",  // Phase B: derive from /api/sessions?status=active
                )
            }
        } catch (t: Throwable) {
            Timber.v("health · fetch failed: ${t.message}")
            CortexStatus(endpoint = wssEndpoint)
        }
    }

    private fun wssToHttpBase(wssUrl: String): String? {
        val u = wssUrl.trim()
        if (!(u.startsWith("wss://") || u.startsWith("ws://"))) return null
        val noScheme = u.substringAfter("://")
        val host = noScheme.substringBefore('/')
        val scheme = if (u.startsWith("wss://")) "https" else "http"
        return "$scheme://$host"
    }
}
