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
 *   - `voice_invoke`  → open the mic for a fresh voice invocation
 *     (ConstellationService.startListening → Idle→Listening). Primary entry
 *     per C-54 (replaces the side button).
 *   - `kill_active`   → cancel the current agent / dismiss HUD
 *   - `shortcut_<id>` → fire the shortcut via [ShortcutFireClient]
 *   - `hud_*`         → HUD-profile gestures (Doc/18 §5) pushed by
 *     [HaloHudProfile] while a card is up; routed to the state-aware
 *     InputHandler so the ring exclusively drives card actions.
 */
class HaloTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: return
        Timber.i("HaloTriggerReceiver · action_id=$actionId")

        when {
            actionId.startsWith("shortcut_") -> {
                // Delegate to the Service — camera capture + HTTP can take more
                // than the 10s a BroadcastReceiver gets even with goAsync().
                // Service.fireShortcut runs in the Service's own CoroutineScope.
                val sid = actionId.removePrefix("shortcut_")
                Timber.i("HaloTrigger · delegating shortcut $sid to ConstellationService")
                ConstellationService.fireShortcut(ctx, sid)
            }
            actionId == "voice_invoke" -> {
                Timber.i("HaloTrigger · voice_invoke → ConstellationService.startListening")
                ConstellationService.startListening(ctx)
            }
            actionId == "kill_active" -> {
                Timber.i("HaloTrigger · kill_active → ConstellationService.killActive")
                ConstellationService.killActive(ctx)
            }
            actionId.startsWith("hud_") -> {
                // HUD-profile gestures pushed while a card is up (Doc/18 §5).
                // Routed to the state-aware InputHandler — the ring now drives
                // what the temple button used to (activate / dismiss / modify /
                // scroll). See HaloHudProfile for the gesture→action map.
                Timber.i("HaloTrigger · $actionId → ConstellationService.hudGesture")
                ConstellationService.hudGesture(ctx, actionId)
            }
            else -> {
                Timber.w("HaloTrigger · unknown action_id $actionId")
            }
        }
    }
}
