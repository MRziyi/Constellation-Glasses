package com.constellation.glass.auth

import com.constellation.glass.BuildConfig
import com.constellation.glass.net.httpRetryOnce
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Programmatic login to the Constellation Edge — no browser needed.
 *
 * Flow:
 *   POST {edge}/api/auth/login  body {"password": "..."}
 *   → 200 with `Set-Cookie: console_session=<value>; HttpOnly; ...`
 *   → we extract (name, value) and hand to CookieStore
 *
 * On 401 the caller surfaces "wrong password" to the user.
 *
 * Endpoint derived from BuildConfig.WSS_URL — replace `wss://` with `https://`
 * and drop the `/ws/glass` path.
 */
object CortexAuth {

    sealed class Result {
        data class Success(val cookie: CookieStore.Cookie) : Result()
        data class BadPassword(val httpCode: Int) : Result()
        data class Throttled(val msg: String) : Result()
        data class NetworkError(val msg: String) : Result()
    }

    private val client by lazy {
        OkHttpClient.Builder()
            // 15s for public Edge over Rokid Glasses WiFi (matches the rest
            // of the HTTP clients post-2026-05-27 WoW tuning).
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Compute the `https://...` base URL from the configured WSS endpoint. */
    fun edgeBaseUrl(): String {
        val wss = BuildConfig.WSS_URL
        val httpish = wss.replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
        // Drop the WS path component (e.g. "/ws/glass")
        val pathStart = httpish.indexOf('/', startIndex = httpish.indexOf("://") + 3)
        return if (pathStart > 0) httpish.substring(0, pathStart) else httpish
    }

    fun login(password: String): Result {
        if (password.isBlank()) return Result.BadPassword(400)
        val url = "${edgeBaseUrl()}/api/auth/login"
        val body = JSONObject().put("password", password).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return try {
            httpRetryOnce(client) { client.newCall(req).execute() }.use { resp ->
                Timber.i("CortexAuth · POST $url → HTTP ${resp.code}")
                when (resp.code) {
                    200 -> {
                        // Extract Set-Cookie header. There may be more than one
                        // attribute (HttpOnly, SameSite, Max-Age, ...); the
                        // raw value is "name=value; HttpOnly; SameSite=Lax".
                        val raw = resp.header("Set-Cookie")
                            ?: return Result.NetworkError("server didn't set a cookie")
                        val (n, v) = parseSetCookie(raw)
                            ?: return Result.NetworkError("bad Set-Cookie: $raw")
                        Result.Success(CookieStore.Cookie(n, v))
                    }
                    401 -> Result.BadPassword(401)
                    429 -> Result.Throttled("Too many tries — wait a few minutes.")
                    else -> Result.NetworkError("HTTP ${resp.code}")
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "CortexAuth · login threw")
            Result.NetworkError(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Parse the first cookie pair out of a Set-Cookie header value.
     *  Returns (name, value) or null if malformed. */
    private fun parseSetCookie(header: String): Pair<String, String>? {
        val firstSemi = header.indexOf(';')
        val pair = if (firstSemi > 0) header.substring(0, firstSemi) else header
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty() || value.isEmpty()) return null
        return name to value
    }
}
