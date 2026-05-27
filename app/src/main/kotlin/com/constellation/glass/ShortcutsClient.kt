package com.constellation.glass

import android.content.Context
import com.constellation.glass.auth.CookieStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * REST client for `/api/shortcuts` (P-app.D.2).
 *
 * Each method is blocking — call from `Dispatchers.IO`. Returns a sealed
 * [Result] sum type so the caller can render a clean error toast.
 *
 * Auth: attaches the `console_session` cookie from [CookieStore] on every
 * request (Edge gates the entire /api/ prefix; without cookie → 401).
 */
object ShortcutsClient {

    /** Plain data the UI renders. Matches the wire shape Cortex returns. */
    data class Shortcut(
        val id: String,
        val name: String,
        val prompt: String,
        val photo: Boolean,
        val created: String,
        val updated: String,
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class HttpError(val code: Int, val body: String) : Result<Nothing>()
        data class NetworkError(val msg: String) : Result<Nothing>()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        // 15s for public Edge over Rokid Glasses WiFi (see CortexHealthClient).
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── Public API ────────────────────────────────────────────────────────

    fun list(ctx: Context, wssEndpoint: String): Result<List<Shortcut>> {
        return doRequest(ctx, wssEndpoint, "GET", "/api/shortcuts", null) { json ->
            val arr = json.optJSONArray("shortcuts") ?: JSONArray()
            (0 until arr.length()).map { i -> parse(arr.getJSONObject(i)) }
        }
    }

    fun create(
        ctx: Context,
        wssEndpoint: String,
        id: String,
        name: String,
        prompt: String,
        photo: Boolean,
    ): Result<Shortcut> {
        val body = JSONObject().apply {
            put("id", id); put("name", name); put("prompt", prompt); put("photo", photo)
        }
        return doRequest(ctx, wssEndpoint, "POST", "/api/shortcuts", body) { json ->
            parse(json.getJSONObject("shortcut"))
        }
    }

    fun update(
        ctx: Context,
        wssEndpoint: String,
        id: String,
        name: String,
        prompt: String,
        photo: Boolean,
    ): Result<Shortcut> {
        val body = JSONObject().apply {
            put("name", name); put("prompt", prompt); put("photo", photo)
        }
        return doRequest(ctx, wssEndpoint, "PUT", "/api/shortcuts/$id", body) { json ->
            parse(json.getJSONObject("shortcut"))
        }
    }

    fun delete(ctx: Context, wssEndpoint: String, id: String): Result<Boolean> {
        return doRequest(ctx, wssEndpoint, "DELETE", "/api/shortcuts/$id", null) { json ->
            json.optBoolean("ok", false)
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun <T> doRequest(
        ctx: Context,
        wssEndpoint: String,
        method: String,
        path: String,
        body: JSONObject?,
        parseOk: (JSONObject) -> T,
    ): Result<T> {
        val base = wssToHttpBase(wssEndpoint)
            ?: return Result.NetworkError("invalid endpoint URL")
        val cookieHeader = CookieStore.read(ctx)?.toHeader()
        return try {
            val rb = Request.Builder().url("$base$path")
            if (cookieHeader != null) rb.header("Cookie", cookieHeader)
            when (method) {
                "GET" -> rb.get()
                "DELETE" -> rb.delete()
                else -> {
                    val payload = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
                    if (method == "PUT") rb.put(payload) else rb.post(payload)
                }
            }
            http.newCall(rb.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Timber.w("shortcuts · $method $path → HTTP ${resp.code} body=${text.take(200)}")
                    return Result.HttpError(resp.code, text)
                }
                val json = if (text.isBlank()) JSONObject() else JSONObject(text)
                Result.Ok(parseOk(json))
            }
        } catch (t: Throwable) {
            Timber.v("shortcuts · $method $path failed: ${t.message}")
            Result.NetworkError(t.message ?: "unknown")
        }
    }

    private fun parse(o: JSONObject): Shortcut = Shortcut(
        id = o.getString("id"),
        name = o.optString("name", ""),
        prompt = o.optString("prompt", ""),
        photo = o.optBoolean("photo", false),
        created = o.optString("created", ""),
        updated = o.optString("updated", ""),
    )

    private fun wssToHttpBase(wssUrl: String): String? {
        val u = wssUrl.trim()
        if (!(u.startsWith("wss://") || u.startsWith("ws://"))) return null
        val noScheme = u.substringAfter("://")
        val host = noScheme.substringBefore('/')
        val scheme = if (u.startsWith("wss://")) "https" else "http"
        return "$scheme://$host"
    }
}
