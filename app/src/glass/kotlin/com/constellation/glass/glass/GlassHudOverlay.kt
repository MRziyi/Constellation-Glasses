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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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

    /** Remove the overlay, sliding the card straight UP off the top edge + fading
     *  (Zack 2026-06-02 — replaces the half-right-slide-then-fade).
     *
     *  We animate the VIEW's `translationY` (not the window's `params.y`): the
     *  window is now FULL-SCREEN ([addOverlayInternal]), so the card has room to
     *  travel up and clip naturally at the screen's top edge. Animating `params.y`
     *  was unreliable — YodaOS clamps/fights an overlay window's position and
     *  leaked its default right-slide on `removeView`. With `windowAnimations=0`
     *  and a transparent full-screen window, `removeView` shows nothing, so no OEM
     *  exit animation can leak. */
    fun detach(onDetached: (() -> Unit)? = null) {
        main.post {
            val v = overlay ?: run { onDetached?.invoke(); return@post }
            overlay = null  // idempotent: a second detach() no-ops
            v.animate().cancel()  // drop any leftover enter animation on this view
            val dist = dpToPx(SLIDE_DISTANCE_DP).toFloat()
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                // Slower + LINEAR so the upward motion is clearly visible (Zack
                // 2026-06-02: at 220ms + a full fade it read as "just vanished").
                duration = EXIT_MS
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    v.translationY = -dist * f
                    // Continuously fade out as it slides up (Zack 2026-06-02).
                    v.alpha = 1f - f
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // Blank the content FIRST (onDetached → snapshot to empty
                        // Idle), THEN removeView a few frames later. YodaOS's
                        // removeView snapshot ignores our translationY/alpha and
                        // would otherwise flash the card back at its layout
                        // position; removing an already-empty surface shows nothing
                        // (Zack 2026-06-02).
                        onDetached?.invoke()
                        main.postDelayed({
                            try { wm.removeView(v) } catch (_: Throwable) {}
                        }, 50L)
                    }
                })
                start()
            }
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
            // FULL-SCREEN window (Zack 2026-06-02): the card is positioned at the
            // top by the Compose Box (TopCenter); a full-screen window gives the
            // card room to slide up and off the screen's top edge on exit (a
            // WRAP_CONTENT window clips a view translation immediately).
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // - NOT_FOCUSABLE: don't steal IME / D-pad focus from the app below.
            // - NOT_TOUCHABLE: the whole (now full-screen) window passes every
            //   touch through to the app below — the HUD has no touch controls
            //   (input is ring OVERLAY_GESTURE broadcasts, scroll via CardScrollBus).
            // - LAYOUT_IN_SCREEN: gravity computed against the full screen.
            // - SHOW_WHEN_LOCKED + TURN_SCREEN_ON: appear over lockscreen + force
            //   the panel on (auto-lock kicks in at ~10s of idle).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            // No window animations: we animate the VIEW (translationY) ourselves
            // for both enter (drop-in) and exit (slide-up). This sidesteps YodaOS
            // ignoring windowExitAnimation + leaking its default right-slide.
            windowAnimations = 0
        }
        // Enter: start above + transparent, then drop into place.
        val dist = dpToPx(SLIDE_DISTANCE_DP).toFloat()
        view.translationY = -dist
        view.alpha = 0f
        try {
            wm.addView(view, params)
            overlay = view
            view.animate()
                .translationY(0f).alpha(1f)
                .setDuration(SLIDE_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            Timber.i("GlassHudOverlay · attached (SYSTEM_ALERT_WINDOW, full-screen)")
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
                // Full-screen window → pin the card to the top-center with a small
                // top margin (was the window's y=16dp before it went full-screen).
                Box(
                    modifier = Modifier.fillMaxSize().padding(top = 16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    AppStateHud(snap)
                }
            }
        }
    }

    private fun dpToPx(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private companion object {
        // Card slide distance for enter/exit. > cardMaxHeight (380dp) + top margin,
        // so even the tallest card fully clears the screen's top edge on exit.
        const val SLIDE_DISTANCE_DP = 440
        const val SLIDE_MS = 220L   // enter (drop-in)
        const val EXIT_MS = 270L    // exit (slide-up + fade) — snappy but legible
    }
}
