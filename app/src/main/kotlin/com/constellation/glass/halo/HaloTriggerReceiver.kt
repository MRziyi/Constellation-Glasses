package com.constellation.glass.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.constellation.glass.ConstellationService
import timber.log.Timber

/**
 * Receives `com.halo.ring.action.TRIGGER` broadcasts from Halo Ring
 * (`halo-ring-plugin-protocol.md` §5). Dispatches by `action_id`:
 *
 *   - `voice_invoke` → open the mic and start a normal voice invocation
 *     (TODO: wire into ConstellationService.startListening)
 *   - `kill_active`  → cancel the current agent / dismiss HUD
 *     (TODO: wire into StateMachine.kill)
 *   - `shortcut_<id>` → fire the shortcut via [ShortcutFireClient]
 *
 * Phase D.5.a wires the `shortcut_*` path. The core action paths
 * (`voice_invoke`, `kill_active`) are still TODO and only log for now.
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
            else -> {
                Timber.w("HaloTrigger · unknown action_id $actionId")
            }
        }
    }
}
