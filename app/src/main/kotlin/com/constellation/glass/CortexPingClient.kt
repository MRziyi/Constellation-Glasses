package com.constellation.glass

import android.content.Context
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.net.httpRetryOnce
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Lightweight ping for the in-app TEST CONNECTION button (P-app.B).
 *
 * Hits `POST <edge>/api/ping` with a 4s timeout. Returns a [PingResult]
 * describing the outcome — both happy paths and failure modes are surfaced
 * to the UI as a short toast line.
 *
 * Why not reuse [CortexHealthClient.fetch]: the in-app status block polls
 * /api/health every 5s for the connection dot. TEST CONNECTION is a
 * user-driven action — we want a fresh probe with the user-edited URL
 * (which may not yet have round-tripped through the polling loop) and a
 * clear pass/fail toast. The ping endpoint also doesn't touch the
 * router/dispatcher, so it's safe to fire from a button-press.
 */
object CortexPingClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        // 15s for public Edge over Rokid Glasses WiFi (see CortexHealthClient).
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed class PingResult {
        data class Ok(val serverBound: Boolean, val toolConn: Boolean) : PingResult()
        data class HttpError(val code: Int) : PingResult()
        data class NetworkError(val msg: String) : PingResult()
    }

    fun fetch(ctx: Context, wssEndpoint: String): PingResult {
        val base = wssToHttpBase(wssEndpoint)
            ?: return PingResult.NetworkError("invalid endpoint URL")
        // Attach auth cookie — Edge requires it on all /api/* routes including
        // /api/ping. Without it the user sees a misleading "HTTP 401 from edge"
        // toast even though the network path works.
        val cookieHeader = CookieStore.read(ctx)?.toHeader()
        return try {
            val emptyBody = "{}".toRequestBody("application/json".toMediaType())
            val reqBuilder = Request.Builder().url("$base/api/ping").post(emptyBody)
            if (cookieHeader != null) reqBuilder.header("Cookie", cookieHeader)
            val req = reqBuilder.build()
            httpRetryOnce(http) { http.newCall(req).execute() }.use { resp ->
                if (!resp.isSuccessful) {
                    Timber.w("ping · HTTP ${resp.code}")
                    return PingResult.HttpError(resp.code)
                }
                val body = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(body) }.getOrNull()
                val ok = json?.optBoolean("ok", false) ?: false
                if (!ok) return PingResult.HttpError(resp.code)
                PingResult.Ok(
                    serverBound = json.optBoolean("server_bound", false),
                    toolConn = json.optBoolean("tool_conn", false),
                )
            }
        } catch (t: Throwable) {
            Timber.v("ping · network failure: ${t.message}")
            PingResult.NetworkError(t.message ?: "unknown")
        }
    }

    fun PingResult.toUserMessage(): String = when (this) {
        is PingResult.Ok -> {
            val extras = listOfNotNull(
                if (serverBound) "server_bound" else null,
                if (toolConn) "tool_conn" else null,
            ).joinToString(" · ")
            "✓ ping ok" + if (extras.isNotEmpty()) " · $extras" else ""
        }
        is PingResult.HttpError -> "✗ HTTP $code from edge"
        is PingResult.NetworkError -> "✗ network: $msg"
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
