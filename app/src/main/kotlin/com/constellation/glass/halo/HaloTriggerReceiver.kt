package com.constellation.glass.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.constellation.glass.ShortcutFireClient
import com.constellation.glass.app.EndpointStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    // We use goAsync() + a short-lived coroutine scope so the broadcast
    // doesn't drop us before the HTTP call returns. The Halo Ring protocol
    // §5.3 says "fire and forget; no ACK required" — we honor that on the
    // sender side but still do the actual work async-safely here.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(ctx: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: return
        Timber.i("HaloTriggerReceiver · action_id=$actionId")

        when {
            actionId.startsWith("shortcut_") -> {
                val sid = actionId.removePrefix("shortcut_")
                val pending = goAsync()
                scope.launch {
                    try {
                        val endpoint = EndpointStore.flow(ctx).first()
                        val result = ShortcutFireClient.fireById(ctx, sid, endpoint)
                        when (result) {
                            is ShortcutFireClient.Result.Ok ->
                                Timber.i("HaloTrigger · shortcut $sid fired (event=${result.eventId})")
                            is ShortcutFireClient.Result.HttpError ->
                                Timber.w("HaloTrigger · shortcut $sid HTTP ${result.code}: ${result.body.take(120)}")
                            is ShortcutFireClient.Result.NetworkError ->
                                Timber.w("HaloTrigger · shortcut $sid network: ${result.msg}")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
            actionId == "voice_invoke" -> {
                // TODO: route to ConstellationService.startListening() once
                //  the Service exposes a static helper for it.
                Timber.i("HaloTrigger · voice_invoke (TODO — not yet wired)")
            }
            actionId == "kill_active" -> {
                // TODO: route to StateMachine.kill() once exposed.
                Timber.i("HaloTrigger · kill_active (TODO — not yet wired)")
            }
            else -> {
                Timber.w("HaloTrigger · unknown action_id $actionId")
            }
        }
    }
}
