package com.constellation.glass.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.constellation.glass.ConstellationService
import timber.log.Timber

/**
 * Receives `com.halo.ring.action.OVERLAY_GESTURE` from Halo Ring while our
 * exclusive overlay is active (RESP-halo-ring-overlay-protocol-v1 §3). The ring
 * forwards a raw `gesture` name (TAP / DOUBLE_TAP / LONG_PRESS / SWIPE_UP /
 * SWIPE_DOWN / …); we own the interpretation. Routed to
 * [ConstellationService.hudGesture] which maps the gesture onto the state-aware
 * InputHandler.
 *
 * The ring sends this with `setPackage(com.constellation.glass)`, so it's an
 * explicit broadcast to us. Exported to receive cross-package.
 */
class HaloOverlayGestureReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val gesture = intent.getStringExtra("gesture") ?: return
        Timber.i("HaloOverlayGestureReceiver · gesture=$gesture")
        ConstellationService.hudGesture(ctx, gesture)
    }
}
