package com.constellation.glass

import android.content.Context
import com.constellation.glass.audio.AudioCapture
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.input.InputHandler

/**
 * Bridges core (state machine + WSS + audio pipeline) to whichever flavor of
 * the app is built:
 *  - `glass`     → on-eyepiece bare-metal app (CXR-L not used)
 *  - `phoneDebug`→ phone-side debug app (SYSTEM_ALERT_WINDOW overlay)
 *
 * Each flavor source set ships a `createPlatformAdapter(Context)` top-level
 * function whose `internal` impl returns a flavor-specific subclass.
 * `ConstellationService.onCreate` calls [HudPlatformAdapter.create].
 *
 * No core code may import flavor-specific platform classes; they're hidden
 * behind this interface.
 */
interface HudPlatformAdapter {

    /** Build (and bring up, if needed) the HUD render surface. */
    fun createHudSurface(): HudSurface

    /** Build the platform-appropriate AudioCapture (mic-aware). */
    fun createAudioCapture(): AudioCapture

    /**
     * Install a platform-specific input source so user gestures route to
     * [handler]. Glass: registers a BroadcastReceiver for system button
     * broadcasts. PhoneDebug: posts a persistent notification with action
     * buttons that simulate the glass key events.
     */
    fun installInputListener(handler: InputHandler)

    /** Detach the input listener. Paired with [installInputListener]. */
    fun uninstallInputListener()

    /** Free any platform resources held (windows, activities, receivers). */
    fun destroy() {}

    companion object {
        /** Resolved at compile time per productFlavor; see [createPlatformAdapter]. */
        fun create(context: Context): HudPlatformAdapter = createPlatformAdapter(context)
    }
}

/**
 * Per-flavor factory function. Each flavor source set (`glass` /
 * `phoneDebug`) provides an `internal fun createPlatformAdapter` in the
 * `com.constellation.glass` package. At build time, only the active flavor's
 * source set is on the classpath, so this call resolves unambiguously.
 *
 * Not declared here in main — declaring it would clash with the flavor copies
 * at compile time. Main code calls [createPlatformAdapter] (resolved from
 * whichever flavor source is being built).
 */

