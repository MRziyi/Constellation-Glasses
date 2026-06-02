package com.constellation.glass.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Headless one-shot still-photo capture.
 *
 * Public entry point: [capture] takes the Application [Context], returns the
 * JPEG-encoded byte array of one frame, then releases the camera. No preview
 * surface — the operation is invisible to the user.
 *
 * **Why headless**: shortcuts fire from contexts without UI (broadcast
 * receiver triggered by Halo Ring gesture, or future on-eyewear "long-press
 * shortcut" path). Showing a preview before snapping defeats the "one-tap
 * fire-and-forget" promise of shortcuts.
 *
 * **Lifecycle**: CameraX requires a [LifecycleOwner] to bind the use case
 * to. We don't have an Activity here, so we own a tiny [LifecycleRegistry]
 * that transitions RESUMED → DESTROYED inside this function, scoped strictly
 * to one capture. `bindToLifecycle` returns a Camera once we're STARTED;
 * the unbind happens via `unbindAll()` after the still completes.
 *
 * **Permission**: caller must have already granted `CAMERA`. The function
 * returns null with a Timber.w log if not — never throws to the user path.
 */
object CameraCapture {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    /**
     * Capture resolution TIER (Zack 2026-05-31). Cortex decides the tier from the
     * ask (the deterministic 「视觉」 cue + a detail qualifier) and sends only the
     * NAME; the glasses map it to pixels here so the actual numbers stay tunable.
     *
     * - STANDARD (1024 px / q85): a fast, low-bandwidth scene glance. The glass is
     *   on Bluetooth PAN (~90 KB/s), so ~150–250 KB / ~2–3 s per photo.
     * - DETAIL (2048 px / q90): the legible-text sweet spot for poster / sign /
     *   document, per the Claude (Opus 4.7+ takes 2576 px) + GPT-5 (high → 2048)
     *   vision docs. Bigger + slower on BT-PAN — used only when the ask wants it.
     *
     * 1024 was too low for small text (Zack saw glyphs smear); q85 also hurts text
     * edges, so the detail tier bumps quality too.
     */
    enum class Tier(val maxEdgePx: Int, val jpegQuality: Int) {
        STANDARD(1024, 85),
        DETAIL(2048, 90),
        ;

        companion object {
            /** Map a Cortex tier name → Tier; unknown/null → STANDARD. */
            fun fromName(name: String?): Tier =
                if (name?.trim()?.lowercase() == "detail") DETAIL else STANDARD
        }
    }

    suspend fun capture(ctx: Context, tier: Tier = Tier.STANDARD): ByteArray? {
        val t0 = System.currentTimeMillis()   // TIMING (2026-05-30): break down the ~10s
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("CameraCapture · CAMERA permission not granted; returning null")
            return null
        }
        val provider = try {
            ProcessCameraProvider.getInstance(ctx).await()
        } catch (t: Throwable) {
            Timber.w(t, "CameraCapture · ProcessCameraProvider unavailable")
            return null
        }
        val tProvider = System.currentTimeMillis()

        // Ask the camera to OUTPUT a frame near the tier's target edge instead of
        // the full ~12 MP sensor (Zack 2026-06-01 capture-speed pass). Capturing +
        // JPEG-encoding a full 4000-px sensor frame and then decoding that ~1.7 MB
        // JPEG was ~2.5 s of the path; selecting a ~1024/2048-px output makes the
        // camera encode a small JPEG and the downscale below decode a small one.
        // CLOSEST_HIGHER_THEN_LOWER → never under-sample (pick ≥ target if it
        // exists), and downscaleAndRecompress still trims to the exact edge.
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(tier.maxEdgePx, tier.maxEdgePx * 3 / 4),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()

        // LifecycleRegistry.setCurrentState() requires the main thread; the
        // owner must therefore be created + advanced + torn down from Main.
        val owner = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            OneShotLifecycleOwner().apply { resume() }
        }

        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            }
            val tBind = System.currentTimeMillis()
            val bytes = takePictureBytes(capture, tier)
            val tShot = System.currentTimeMillis()
            Timber.i(
                "CameraCapture · TIMING tier=${tier.name} provider=${tProvider - t0}ms " +
                    "bind=${tBind - tProvider}ms takePicture+downscale=${tShot - tBind}ms " +
                    "total=${tShot - t0}ms bytes=${bytes?.size ?: 0}"
            )
            bytes
        } catch (t: Throwable) {
            Timber.w(t, "CameraCapture · capture failed")
            null
        } finally {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    provider.unbindAll()
                    owner.destroy()
                }
            } catch (_: Throwable) {}
        }
    }

    private suspend fun takePictureBytes(capture: ImageCapture, tier: Tier): ByteArray? =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ioExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buf = image.planes[0].buffer
                            val rawJpeg = ByteArray(buf.remaining()).also { buf.get(it) }
                            // CameraX gives the sensor→upright rotation here; the
                            // raw JPEG's pixels are in sensor orientation. We fold
                            // it into the downscale matrix (decode+re-encode would
                            // otherwise drop EXIF → sideways image).
                            val downscaled = downscaleAndRecompress(
                                rawJpeg, image.imageInfo.rotationDegrees,
                                tier.maxEdgePx, tier.jpegQuality,
                                // STANDARD is a scene glance → RGB_565 halves the
                                // transient bitmap (no alpha needed for a photo);
                                // DETAIL keeps ARGB_8888 for legible small text.
                                preferRgb565 = tier == Tier.STANDARD,
                            )
                            Timber.i(
                                "CameraCapture · downscaled ${rawJpeg.size}B → " +
                                    "${downscaled?.size ?: -1}B (${tier.maxEdgePx}px max, q=${tier.jpegQuality})"
                            )
                            cont.resume(downscaled ?: rawJpeg)
                        } catch (t: Throwable) {
                            Timber.w(t, "CameraCapture · image read failed")
                            cont.resume(null)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Timber.w(exception, "CameraCapture · takePicture error")
                        cont.resume(null)
                    }
                },
            )
        }

    /**
     * Decode raw JPEG → downscale to [maxEdgePx] longest edge → re-encode at
     * [jpegQuality]. Returns null on decode failure (caller falls back to the
     * raw JPEG so the request still goes out). The target px/quality come from
     * the capture [Tier] (standard 1024/q85 vs detail 2048/q90).
     *
     * With the ResolutionSelector above the camera now hands us a frame already
     * near the target edge (not the full ~12 MP sensor), so this decode is cheap
     * and inSampleSize is usually 1 — it stays as the exact-edge trim + rotation +
     * quality re-encode. Output: standard ≈ 150–250 KB, detail ≈ 0.5–1 MB; sent as
     * a binary WS frame (no base64 +33%).
     */
    private fun downscaleAndRecompress(
        raw: ByteArray, rotationDegrees: Int, maxEdgePx: Int, jpegQuality: Int,
        preferRgb565: Boolean = false,
    ): ByteArray? {
        val opts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        val srcW = opts.outWidth
        val srcH = opts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // Use BitmapFactory's inSampleSize to do a cheap first-pass downscale
        // (powers of 2 only). The final exact-edge scale + sensor rotation are
        // then folded into ONE Bitmap.createBitmap (Zack 2026-06-02 memory pass):
        // the old path allocated a separate createScaledBitmap AND a rotate copy
        // (3 full bitmaps, 2 live at peak); merging the scale+rotate matrix drops
        // it to 2 allocations. RGB_565 (standard tier) halves each bitmap.
        var sample = 1
        while (srcW / (sample * 2) >= maxEdgePx &&
            srcH / (sample * 2) >= maxEdgePx
        ) {
            sample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (preferRgb565) inPreferredConfig = Bitmap.Config.RGB_565
        }
        val coarse = BitmapFactory.decodeByteArray(raw, 0, raw.size, decodeOpts) ?: return null

        val longest = maxOf(coarse.width, coarse.height)
        val scale = if (longest > maxEdgePx) maxEdgePx.toFloat() / longest else 1f
        val needScale = scale < 1f
        val needRotate = rotationDegrees % 360 != 0

        // One createBitmap applies BOTH the exact-edge scale and the sensor→upright
        // rotation (folding rotation in avoids the decode/re-encode dropping EXIF →
        // sideways image). Skips the alloc entirely when neither is needed.
        val out = if (!needScale && !needRotate) {
            coarse
        } else {
            val m = android.graphics.Matrix().apply {
                if (needScale) postScale(scale, scale)
                if (needRotate) postRotate(rotationDegrees.toFloat())
            }
            Bitmap.createBitmap(coarse, 0, 0, coarse.width, coarse.height, m, true)
                .also { if (it !== coarse) coarse.recycle() }
        }

        val baos = ByteArrayOutputStream(64 * 1024)
        out.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
        out.recycle()
        return baos.toByteArray()
    }
}

/**
 * A one-shot [LifecycleOwner] used to scope a single [androidx.camera.lifecycle.ProcessCameraProvider]
 * binding. Starts at RESUMED; [destroy] transitions to DESTROYED which CameraX
 * uses to release the camera if the caller forgot to unbind.
 */
private class OneShotLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = registry

    /** Must be called from the main thread. Advances INITIALIZED → RESUMED. */
    fun resume() {
        registry.currentState = Lifecycle.State.CREATED
        registry.currentState = Lifecycle.State.STARTED
        registry.currentState = Lifecycle.State.RESUMED
    }

    /** Must be called from the main thread. */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
