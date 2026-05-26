package com.constellation.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Restart [ConstellationService] on device boot. Without this the service
 * would only run after the user opens the (non-existent) app or triggers
 * a Halo Ring action — the user has no manual entry point.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
            )
        ) return
        Timber.i("BootReceiver · ${intent.action} → starting ConstellationService")
        ConstellationService.start(ctx)
    }
}
