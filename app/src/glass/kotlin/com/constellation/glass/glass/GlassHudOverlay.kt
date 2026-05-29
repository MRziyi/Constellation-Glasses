package com.constellation.glass.glass

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.constellation.glass.hud.OverlayHostOwner
import com.constellation.glass.hud.composables.AppStateHud
import timber.log.Timber

/**
 * On-eyepiece HUD via a SYSTEM_ALERT_WINDOW system-level overlay.
 *
 * **2026-05-28 pivot — replaces [GlassHudActivity]** (deleted). Real-device
 * feedback: a fullscreen transparent Activity took over the panel completely,
 * even though most pixels were unlit (= AR transparent), the Activity
 * mechanically owned the panel and the user couldn't see launcher / other
 * apps "behind" it on the system level. SYSTEM_ALERT_WINDOW is a real
 * floating overlay above all apps including the launcher — the panel below
 * stays visible (whatever was there before the HUD appeared continues to
 * render, with the HUD card on top).
 *
 * The actual card visual is in [com.constellation.glass.hud.composables.CardFrame]
 * and the per-state content in [AppStateHud]. This file is just the
 * WindowManager host plumbing.
 *
 * Mirrors [com.constellation.glass.phonedebug.PhoneDebugHudSurface] for code
 * reuse — both flavors now use the same overlay-and-Compose pattern. The
 * only difference is phoneDebug wraps the same content in a "GLASS SIM"
 * simulator box; this glass-flavor host is bare (no simulator chrome on
 * actual eyewear).
 */
class GlassHudOverlay(private val ctx: Context) {

    private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    /** Compose lifecycle/savedstate owner — overlay isn't an Activity. */
    private val hostOwner = OverlayHostOwner()

    private val powerManager: PowerManager =
        ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    val canDraw: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(ctx)
        } else true

    /**
     * Attach the overlay to the WindowManager. Idempotent — second call is a
     * no-op if the overlay is already attached.
     *
     * Caller is responsible for [canDraw] being true. If false, [HudSurface]
     * falls back to the logging surface.
     */
    fun attach() {
        if (!canDraw) {
            Timber.w("GlassHudOverlay · SYSTEM_ALERT_WINDOW not granted — overlay disabled")
            return
        }
        if (overlay != null) return
        main.post { addOverlayInternal() }
    }

    /** Remove the overlay window from the WindowManager. */
    fun detach() {
        main.post {
            overlay?.let {
                try { wm.removeView(it) } catch (_: Throwable) {}
            }
            overlay = null
        }
    }

    fun destroy() {
        detach()
        wakeLock?.release()
        wakeLock = null
        hostOwner.destroy()
    }

    /**
     * Wake-on while a HUD is visible. Acquires `SCREEN_BRIGHT_WAKE_LOCK |
     * ACQUIRE_CAUSES_WAKEUP` indefinitely (with a 5-min hard ceiling for
     * safety — a stuck non-Idle state shouldn't drain forever).
     *
     * Rokid Glasses auto-locks the panel after ~10s of idle; without holding
     * a wake lock the panel would go black mid-card-view (typical TTL 30s+).
     * Per user feedback 2026-05-28: "更新时点亮屏幕 (现在默认10s熄屏)".
     *
     * [release] when the HUD state returns to Idle.
     */
    fun wakeOn() {
        if (wakeLock?.isHeld == true) return
        @Suppress("DEPRECATION")  // SCREEN_BRIGHT_WAKE_LOCK is deprecated but still the
        // correct API for non-Activity overlay contexts.
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "Constellation:HudVisible",
        ).apply {
            setReferenceCounted(false)
            try {
                acquire(5 * 60_000L)  // 5-min ceiling
                Timber.i("GlassHudOverlay · WakeLock acquired (5 min ceiling)")
            } catch (t: Throwable) {
                Timber.w(t, "GlassHudOverlay · WakeLock.acquire failed")
            }
        }
    }

    /** Release the wake lock (caller signals "HUD no longer visible"). */
    fun wakeOff() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            Timber.i("GlassHudOverlay · WakeLock released")
        } catch (_: Throwable) { /* already released */ }
        wakeLock = null
    }

    // ── overlay attach ─────────────────────────────────────────────────────

    private fun addOverlayInternal() {
        val view = buildOverlayView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // - NOT_FOCUSABLE: don't steal IME / D-pad focus from whatever app
            //   is below (HUD has no interactive controls — physical key
            //   broadcasts go through SystemKeyReceiver, not Compose touches).
            // - NOT_TOUCH_MODAL: touches outside the card area pass through to
            //   the app below.
            // - LAYOUT_IN_SCREEN: so gravity is computed against full screen.
            // - SHOW_WHEN_LOCKED + TURN_SCREEN_ON: appear over lockscreen and
            //   force the panel on when the window appears (real-device
            //   requirement; auto-lock kicks in at ~10s of idle).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(16)  // small top margin
            // Drop in from the top, slide UP to exit (replaces YodaOS's default
            // right-slide on removeView). See res/anim/hud_window_{enter,exit}.
            windowAnimations = com.constellation.glass.R.style.HudWindowAnimation
        }
        try {
            wm.addView(view, params)
            overlay = view
            Timber.i("GlassHudOverlay · attached (SYSTEM_ALERT_WINDOW)")
        } catch (t: Throwable) {
            Timber.w(t, "GlassHudOverlay · addView failed")
        }
    }

    /**
     * Build the root view. Single ComposeView. ViewTree owners attached on the
     * ComposeView itself (Compose walks up to rootView to find them — for a
     * WindowManager-attached View, rootView == the View we addView'd).
     */
    private fun buildOverlayView(): View {
        return ComposeView(ctx).apply {
            // ViewTree owners (lifecycle / savedstate / viewmodel) must live on
            // the rootView. For WindowManager-attached views the View itself is
            // the rootView; for nested layouts they live on the outer LinearLayout.
            setViewTreeLifecycleOwner(hostOwner)
            setViewTreeViewModelStoreOwner(hostOwner)
            setViewTreeSavedStateRegistryOwner(hostOwner)
            setContent {
                val snap by GlassHudState.snapshot.collectAsState()
                AppStateHud(snap)
            }
        }
    }

    private fun dpToPx(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
