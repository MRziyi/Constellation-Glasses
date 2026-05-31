package com.constellation.glass

import android.content.Context
import com.constellation.glass.app.ui.CortexStatus
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.net.httpRetryOnce
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
 * We swap `wss://`→`https://` and strip the path. The endpoint comes from
 * [com.constellation.glass.app.EndpointStore] (set by the pairing QR), not a
 * hard-coded build constant.
 */
object CortexHealthClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        // Tuned for public Edge (edge.example.com) over Rokid Glasses WiFi
        // — old 3s timeouts were fine on local Tailscale but too aggressive
        // for TLS handshake after WoW deep-sleep.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun fetch(ctx: Context, wssEndpoint: String): CortexStatus {
        val baseUrl = wssToHttpBase(wssEndpoint) ?: return CortexStatus()
        // Edge requires auth cookie on all /api/* routes including /api/health.
        val cookieHeader = CookieStore.read(ctx)?.toHeader()
        return try {
            val reqBuilder = Request.Builder().url("$baseUrl/api/health").get()
            if (cookieHeader != null) reqBuilder.header("Cookie", cookieHeader)
            val req = reqBuilder.build()
            httpRetryOnce(http) { http.newCall(req).execute() }.use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("health · HTTP ${resp.code}")
                    return CortexStatus()
                }
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                // Two shapes can come back:
                //   Edge:   {"status":"ok","edge":"console-edge","upstream":"...","push_enabled":true,"push_subs":0}
                //   Cortex: {"status":"ok","ts":"...","stats":{...},"server_bound":true,"tool_conn":...}
                // The Glass app reaches Cortex *through* the Edge, so the
                // 200/status=ok from Edge is the strongest signal we get:
                // "the network path to my edge works". If Edge can't talk
                // upstream, we'd see HTTP 502 here.
                val statusOk = json.optString("status") == "ok"
                val stats = json.optJSONObject("stats")
                val dispatches = stats?.optInt("dispatches_total", 0) ?: 0
                CortexStatus(
                    connected = statusOk,
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
