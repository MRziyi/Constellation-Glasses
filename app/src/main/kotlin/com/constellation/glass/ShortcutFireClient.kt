package com.constellation.glass

import android.content.Context
import android.util.Base64
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.camera.CameraCapture
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Fires a shortcut: bundles the preset prompt (and optionally a photo)
 * into a `user_invoke` and ships it to Cortex via the existing
 * `POST /api/test/invoke` endpoint.
 *
 * Phase D.5.a (this commit): text-only. The `photo: true` flag is recorded
 * but not yet honored — we still send the prompt as a `modality=text`
 * invoke. Phase D.5.b will add CameraX capture + base64 attach for
 * shortcuts with photo=true.
 *
 * Why HTTP and not the WSS path used for normal voice invocations: the
 * WSS path is owned by `ConstellationService`, which keeps it for the
 * mic-driven flow. Going via HTTP lets us fire from any context (broadcast
 * receiver, content provider trigger, debug notification button) without
 * an IPC dance into the Service.
 */
object ShortcutFireClient {

    sealed class Result {
        data class Ok(val eventId: String) : Result()
        data class HttpError(val code: Int, val body: String) : Result()
        data class NetworkError(val msg: String) : Result()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        // 15s for public Edge over Rokid Glasses WiFi. readTimeout 30s
        // because shortcut fire can include the full Cortex turnaround
        // (classifier + router + tool dispatch + vision API on photo shortcuts).
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fire a shortcut by id. Reads the shortcut from [ShortcutsLocalCache]
     * (the same cache HaloActionsProvider serves Halo Ring's picker from).
     * Returns [Result.NetworkError] with a descriptive message if the
     * shortcut id isn't known locally.
     *
     * For `photo: true` shortcuts (D.5.b), captures a single frame from the
     * back camera via [CameraCapture] and attaches it as base64 `image` in
     * the user_invoke payload — Cortex's `/api/test/invoke` accepts that
     * field directly.
     *
     * Endpoint comes from [EndpointStore] via the blocking `read` helper —
     * called from background threads only.
     */
    suspend fun fireById(ctx: Context, shortcutId: String, endpoint: String): Result {
        val sc = ShortcutsLocalCache.read(ctx).firstOrNull { it.id == shortcutId }
            ?: return Result.NetworkError("unknown shortcut id '$shortcutId'")
        Timber.i("ShortcutFire · id=$shortcutId photo=${sc.photo}")

        val imageB64: String? = if (sc.photo) {
            val bytes = CameraCapture.capture(ctx)
            if (bytes == null) {
                Timber.w("ShortcutFire · photo capture returned null; sending text-only")
                null
            } else {
                Timber.i("ShortcutFire · captured ${bytes.size} bytes")
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } else null

        return invoke(ctx, endpoint, sc.prompt, imageB64)
    }

    private fun invoke(ctx: Context, wssEndpoint: String, text: String, imageB64: String?): Result {
        val base = wssToHttpBase(wssEndpoint) ?: return Result.NetworkError("invalid endpoint")
        val cookie = CookieStore.read(ctx)?.toHeader()
        val payload = JSONObject().apply {
            put("text", text)
            put("modality", if (imageB64 != null) "vision" else "text")
            if (imageB64 != null) put("image", imageB64)
        }
        return try {
            val rb = Request.Builder().url("$base/api/test/invoke")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
            if (cookie != null) rb.header("Cookie", cookie)
            http.newCall(rb.build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Timber.w("ShortcutFire · HTTP ${resp.code} body=${body.take(200)}")
                    return Result.HttpError(resp.code, body)
                }
                val json = if (body.isBlank()) JSONObject() else JSONObject(body)
                Result.Ok(json.optString("event_id", "?"))
            }
        } catch (t: Throwable) {
            Timber.v("ShortcutFire · network failure: ${t.message}")
            Result.NetworkError(t.message ?: "unknown")
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
