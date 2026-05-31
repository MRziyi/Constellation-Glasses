package com.constellation.glass

import android.content.Context
import android.util.Base64
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.camera.CameraGate
import com.constellation.glass.net.httpRetryOnce
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
 * A slot with sendPhoto=true captures a frame UPFRONT via CameraGate and
 * attaches it as base64 `image` (modality=vision); sendPhoto=false sends the
 * prompt as text. (Capture-resolution tiering — standard 1080p vs detail 2K —
 * lands with the glass-side CameraCapture tier work.)
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
     * Fire one of the three fixed slots (1..3) by index. Reads the slot from
     * [ShortcutSlots]. No-op [Result.NetworkError] if the slot is empty
     * (unconfigured) — nothing to send.
     *
     * `sendPhoto=true` captures a frame UPFRONT via [CameraGate] and attaches
     * it as base64 `image`. Because the image is present, Cortex's R-13
     * on-demand pull (`_looks_visual_intent` + `image is None`) does NOT fire —
     * so a "what's in front" slot doesn't double-capture. `sendPhoto=false`
     * sends the prompt as text (server may still pull on demand if visual).
     *
     * Endpoint comes from [EndpointStore]; called from background threads only.
     */
    suspend fun fireBySlot(ctx: Context, index: Int, endpoint: String): Result {
        val slot = ShortcutSlots.get(ctx, index)
            ?: return Result.NetworkError("no slot $index")
        if (!slot.configured) {
            Timber.i("ShortcutFire · slot $index is empty; nothing to fire")
            return Result.NetworkError("slot $index unconfigured")
        }
        Timber.i("ShortcutFire · slot $index '${slot.label}' photo=${slot.sendPhoto}")

        val imageB64: String? = if (slot.sendPhoto) {
            // On Rokid Glasses, AppOps CAMERA:foreground requires a RESUMED
            // Activity in our process even with foregroundServiceType=camera.
            // Route through CameraGateActivity (transparent throwaway Activity,
            // ~2s while CameraX captures, then finishes).
            val bytes = CameraGate.captureViaGate(ctx, slot.tier)
            if (bytes == null) {
                Timber.w("ShortcutFire · photo capture returned null; sending text-only")
                null
            } else {
                Timber.i("ShortcutFire · captured ${bytes.size} bytes (via CameraGate)")
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } else null

        return invoke(ctx, endpoint, slot.prompt, imageB64)
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
            httpRetryOnce(http) { http.newCall(rb.build()).execute() }.use { resp ->
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
