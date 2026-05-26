package com.constellation.glass.halo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Receives `com.halo.ring.action.TRIGGER` broadcasts from Halo Ring.
 * Phase 3b.1: log only — Phase 3b.3 wires action_id → state-machine command.
 */
class HaloTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val actionId = intent.getStringExtra("action_id") ?: return
        Timber.i("HaloTriggerReceiver · action_id=$actionId")
        // TODO Phase 3b.3:
        //   - look up action_id (voice_invoke / kill_active / scroll_up / approve / ...)
        //   - route to StateMachine.onRingAction(actionId)
    }
}
