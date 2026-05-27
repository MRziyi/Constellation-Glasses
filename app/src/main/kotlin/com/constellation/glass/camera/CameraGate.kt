package com.constellation.glass.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One-shot transparent Activity whose sole purpose is to be in the foreground
 * long enough to satisfy AppOps `CAMERA: foreground` mode while CameraX captures
 * a frame.
 *
 * **Why this exists** (2026-05-28): On Rokid Glasses (YodaOS-Sprite), the
 * `foregroundServiceType="camera"` declaration is **not** enough to override
 * the AppOps `CAMERA: foreground` UID mode — backgrounded Service-initiated
 * camera open fails with `ERROR_CAMERA_DISABLED while in OPENING state` +
 * `connectHelper:1853: Camera "0" disabled by policy`. The exact same code
 * works on OnePlus 9 (which respects the FGS camera type as an "in-use"
 * marker). YodaOS doesn't — it strictly requires a visible/resumed Activity
 * in our process.
 *
 * Pattern (matches Rokid's own Sprite `com.rokid.os.sprite.assist.media.page.CameraActivity`):
 *   1. Service-side caller calls [CameraGate.captureViaGate]
 *   2. Helper launches this Activity with FLAG_ACTIVITY_NEW_TASK
 *   3. Activity becomes RESUMED → AppOps lifts camera-use restriction
 *   4. Activity invokes [CameraCapture.capture] on its lifecycle
 *   5. Bytes (or null) returned via the shared [pending] CompletableDeferred
 *   6. Activity finishes
 *
 * The Activity is themed transparent (no UI) so the wearer sees nothing on
 * the panel except a sub-second blink — but the panel pixels ARE briefly
 * "owned" by this Activity (they have to be, per AppOps).
 */
object CameraGate {

    /** Serialize captures — only one CameraGateActivity at a time. */
    private val mutex = Mutex()
    private var pending: CompletableDeferred<ByteArray?>? = null

    /**
     * Suspend until a CameraGateActivity instance completes its capture. Safe
     * to call from a Service's CoroutineScope.
     */
    suspend fun captureViaGate(ctx: Context): ByteArray? = mutex.withLock {
        val deferred = CompletableDeferred<ByteArray?>()
        pending = deferred
        val intent = Intent(ctx, CameraGateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        ctx.startActivity(intent)
        try {
            withContext(Dispatchers.Default) {
                deferred.await()
            }
        } finally {
            pending = null
        }
    }

    /** Called from the Activity once capture is done (or failed). */
    fun complete(result: ByteArray?) {
        pending?.complete(result)
    }
}

/**
 * The one-shot Activity itself. Transparent, no animation, finishes after capture.
 * Declared in the merged manifest (main/AndroidManifest.xml) with
 * `android:theme="@android:style/Theme.Translucent.NoTitleBar"`.
 */
class CameraGateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("CameraGateActivity · onCreate")
        // We exist solely to be foreground for AppOps. The Activity is fully
        // transparent (theme set in manifest); the wearer doesn't see content.
        lifecycleScope.launch {
            val bytes = try {
                CameraCapture.capture(this@CameraGateActivity)
            } catch (t: Throwable) {
                Timber.w(t, "CameraGateActivity · capture failed")
                null
            }
            Timber.i("CameraGateActivity · capture done, bytes=${bytes?.size ?: 0}; finishing")
            CameraGate.complete(bytes)
            finish()
        }
    }

    override fun onDestroy() {
        // If the Activity was destroyed before completing (e.g. user
        // double-clicked back), wake the waiter with null so the Service
        // doesn't hang.
        CameraGate.complete(null)
        super.onDestroy()
    }
}
