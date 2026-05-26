package com.constellation.glass

import android.content.Context
import com.constellation.glass.audio.AudioCapture
import com.constellation.glass.glass.GlassAudioCapture
import com.constellation.glass.glass.GlassHudSurface
import com.constellation.glass.glass.SystemKeyReceiver
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.input.InputHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/**
 * Glass-flavor `HudPlatformAdapter`. Wires:
 *  - HUD → [GlassHudSurface] (drives [GlassHudActivity])
 *  - Audio → [GlassAudioCapture] (ChannelMask 0x6000FC + ch0 deinterleave)
 *  - Input → [SystemKeyReceiver] (R08 system button broadcasts)
 *
 * Selected at compile time when `productFlavor == glass`.
 */
internal class GlassPlatformAdapter(private val ctx: Context) : HudPlatformAdapter {

    private val captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keyReceiver: SystemKeyReceiver? = null
    private var hud: HudSurface? = null

    override fun createHudSurface(): HudSurface {
        Timber.i("GlassPlatformAdapter · createHudSurface")
        return GlassHudSurface(ctx).also { hud = it }
    }

    override fun createAudioCapture(): AudioCapture = GlassAudioCapture(ctx, captureScope)

    override fun installInputListener(handler: InputHandler) {
        val rcv = SystemKeyReceiver(handler)
        val filter = SystemKeyReceiver.intentFilter()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(rcv, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(rcv, filter)
        }
        keyReceiver = rcv
        Timber.i("GlassPlatformAdapter · SystemKeyReceiver registered priority=100")
    }

    override fun uninstallInputListener() {
        keyReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        keyReceiver = null
    }

    override fun destroy() {
        hud?.destroy()
        hud = null
        captureScope.coroutineContext[Job]?.cancel()
    }
}

/** Per-flavor factory hook — see `HudPlatformAdapter.kt` in main. */
internal fun createPlatformAdapter(context: Context): HudPlatformAdapter =
    GlassPlatformAdapter(context)
