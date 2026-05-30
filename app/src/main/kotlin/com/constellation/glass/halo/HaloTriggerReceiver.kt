package com.constellation.glass.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.constellation.glass.ConstellationService
import timber.log.Timber

/**
 * Receives `com.halo.ring.action.TRIGGER` broadcasts from Halo Ring
 * (Doc/18 — plugin protocol). Dispatches by `action_id`:
 *
 *   - `voice_invoke`     → open the mic for a fresh voice invocation
 *     (ConstellationService.startListening → Idle→Listening). Primary entry
 *     per C-54 (replaces the side button).
 *   - `shortcut1/2/3`    → fire a fixed slot via [ShortcutFireClient]
 *
 * (Killing/approving/scrolling are in-HUD ring gestures via OVERLAY_GESTURE,
 * not wake actions, so they're not exposed here.)
 *
 * These are the wearer's profile-bound actions for invoking Constellation from
 * IDLE. In-HUD ring control is a separate path: while a card is up we claim the
 * ring as an exclusive overlay ([HaloOverlay]) and the ring forwards raw
 * gestures via OVERLAY_GESTURE → [HaloOverlayGestureReceiver].
 */
class HaloTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: return
        Timber.i("HaloTriggerReceiver · action_id=$actionId")

        when {
            actionId.matches(Regex("shortcut[1-3]")) -> {
                // One of the three fixed slots. Delegate to the Service —
                // camera capture + HTTP exceed a BroadcastReceiver's ~10s
                // budget; Service.fireShortcut runs in its own CoroutineScope.
                Timber.i("HaloTrigger · delegating $actionId to ConstellationService")
                ConstellationService.fireShortcut(ctx, actionId)
            }
            actionId == "voice_invoke" -> {
                Timber.i("HaloTrigger · voice_invoke → ConstellationService.startListening")
                ConstellationService.startListening(ctx)
            }
            actionId == "debug_listen" -> {
                // DEBUG wake (Zack 2026-05-30): identical to voice_invoke, but a
                // separate action_id so a dev gesture can drive a listen cycle
                // from Idle during testing without rebinding the real voice port.
                Timber.i("HaloTrigger · debug_listen (dev wake) → startListening")
                ConstellationService.startListening(ctx)
            }
            else -> {
                Timber.w("HaloTrigger · unknown action_id $actionId")
            }
        }
    }
}
