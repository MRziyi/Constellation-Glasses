package com.constellation.glass

import android.content.Context
import com.constellation.glass.audio.AudioCapture
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.hud.LoggingHudSurface
import com.constellation.glass.input.InputHandler
import com.constellation.glass.phonedebug.DebugInputController
import com.constellation.glass.phonedebug.PhoneAudioCapture
import com.constellation.glass.phonedebug.PhoneDebugHudSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * phoneDebug flavor's `HudPlatformAdapter` implementation. Selected when
 * `productFlavor == phoneDebug`. Backs the HUD with a SYSTEM_ALERT_WINDOW
 * overlay, uses standard mono AudioRecord, and routes "input" through
 * notification action buttons.
 */
internal class PhoneDebugPlatformAdapter(private val ctx: Context) : HudPlatformAdapter {

    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inputController = DebugInputController(ctx)
    private var hud: HudSurface? = null

    override fun createHudSurface(): HudSurface {
        val canOverlay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(ctx)
        } else true
        return if (canOverlay) {
            Timber.i("PhoneDebugPlatformAdapter · using PhoneDebugHudSurface overlay")
            PhoneDebugHudSurface(ctx).also { hud = it }
        } else {
            Timber.w("PhoneDebugPlatformAdapter · overlay perm missing → logging surface")
            LoggingHudSurface().also { hud = it }
        }
    }

    override fun createAudioCapture(): AudioCapture = PhoneAudioCapture(ctx, captureScope)

    override fun installInputListener(handler: InputHandler) {
        inputController.install(handler)
    }

    override fun uninstallInputListener() {
        inputController.uninstall()
    }

    override fun destroy() {
        hud?.destroy()
        hud = null
        // captureScope outlives one capture session by design; cancel on full
        // service shutdown.
        captureScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

/**
 * Per-flavor factory hook resolved by `HudPlatformAdapter.create(ctx)`.
 * The matching glass-flavor copy lives in `src/glass/`.
 */
internal fun createPlatformAdapter(context: Context): HudPlatformAdapter =
    PhoneDebugPlatformAdapter(context)
