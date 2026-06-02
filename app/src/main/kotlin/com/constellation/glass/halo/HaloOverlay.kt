package com.constellation.glass.halo

import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Halo Ring exclusive-overlay control (RESP-halo-ring-overlay-protocol-v1).
 *
 * While the HUD is visible, Constellation owns the WHOLE ring: we send
 * OVERLAY_ACTIVATE and the ring forwards every raw gesture to us (via
 * [HaloOverlayGestureReceiver]) while leaking nothing to the underlying app —
 * no DPAD passthrough, no launcher navigation, no system gestures. We own all
 * gesture semantics + on-HUD prompts; the ring only forwards raw gesture names.
 *
 * Lifecycle:
 *   - [activate] on HUD-open. Must be RE-SENT every ~25 s as keepalive — the
 *     ring auto-releases after 60 s of silence so a crashed/hung Constellation
 *     can't lock the wearer out (the Service runs the keepalive loop).
 *   - [deactivate] on Idle/Offline (and onDestroy) — restores the wearer's
 *     underlying profile instantly.
 *
 * Gated by signature|privileged `PUSH_PROFILE` (granted — shared cert with the
 * ring). Target is the installed Rokid flavor `com.halo.ring.rokid`. All
 * broadcasts are fire-and-forget — no-op if the ring isn't installed/running.
 */
object HaloOverlay {

    private const val RING_PKG = "com.halo.ring.rokid"
    private const val ACTION_ACTIVATE = "com.halo.ring.action.OVERLAY_ACTIVATE"
    private const val ACTION_DEACTIVATE = "com.halo.ring.action.OVERLAY_DEACTIVATE"
    private const val PROFILE_ID = "constellation_hud"
    private const val DISPLAY_NAME = "Constellation"

    /** Keepalive cadence — re-send ACTIVATE within the ring's 60 s timeout. */
    const val KEEPALIVE_MS = 25_000L

    // Raw gesture names the ring forwards via OVERLAY_GESTURE (protocol §2).
    const val G_TAP = "TAP"
    const val G_DOUBLE_TAP = "DOUBLE_TAP"
    const val G_LONG_PRESS = "LONG_PRESS"
    const val G_SWIPE_UP = "SWIPE_UP"
    const val G_SWIPE_DOWN = "SWIPE_DOWN"
    // Compound tap-then-swipe (protocol §2). Decisions use these now instead of
    // bare TAP/DOUBLE_TAP — a deliberate gesture can't be mis-triggered the way
    // a stray tap sent an email (Zack 2026-06-02).
    const val G_TAP_SWIPE_UP = "TAP_SWIPE_UP"
    const val G_TAP_SWIPE_DOWN = "TAP_SWIPE_DOWN"

    /** Claim the ring for the HUD. Idempotent — re-sending refreshes the
     *  keepalive; a new owner replaces a prior overlay. */
    fun activate(ctx: Context) {
        val owner = ctx.packageName
        val intent = Intent(ACTION_ACTIVATE).apply {
            setPackage(RING_PKG)
            putExtra("owner_package", owner)
            putExtra("profile_id", PROFILE_ID)
            putExtra("display_name", DISPLAY_NAME)
        }
        try {
            ctx.sendBroadcast(intent)
            Timber.i("HaloOverlay · OVERLAY_ACTIVATE $owner/$PROFILE_ID → $RING_PKG")
        } catch (t: Throwable) {
            Timber.w(t, "HaloOverlay · OVERLAY_ACTIVATE failed")
        }
    }

    /** Release the ring back to the wearer's underlying profile. */
    fun deactivate(ctx: Context) {
        val owner = ctx.packageName
        val intent = Intent(ACTION_DEACTIVATE).apply {
            setPackage(RING_PKG)
            putExtra("owner_package", owner)
            putExtra("profile_id", PROFILE_ID)
        }
        try {
            ctx.sendBroadcast(intent)
            Timber.i("HaloOverlay · OVERLAY_DEACTIVATE $owner/$PROFILE_ID → $RING_PKG")
        } catch (t: Throwable) {
            Timber.w(t, "HaloOverlay · OVERLAY_DEACTIVATE failed")
        }
    }
}
