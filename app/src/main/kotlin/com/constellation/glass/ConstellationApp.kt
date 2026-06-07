package com.constellation.glass

import android.app.Application
import timber.log.Timber

/**
 * App entry point. **Intentionally minimal** — only plants Timber.
 *
 * **Do NOT start [ConstellationService] from here** (2026-05-29 P1.8 finding):
 * Application.onCreate runs in a "background" context per Android 12+ BAL
 * rules — calling `startForegroundService` here causes the subsequent
 * `startForeground` to be silently denied (`allowStartForeground=-1`,
 * `isForeground=false` despite the notification being posted). The process
 * is then placed at PERCEPTIBLE_APP_ADJ (200) instead of
 * FOREGROUND_SERVICE_ADJ (100) and gets killed under memory pressure.
 *
 * Service-start lifecycle:
 *  - **Cold start via launcher / `am start MainActivity`** → user gesture
 *    is foreground-allowed → [MainActivity.onCreate] starts the Service
 *    once a cookie is present (or after first login).
 *  - **Post-boot auto-start** → [BootReceiver] gets the special Android 12+
 *    boot-grace-period exemption for FGS start.
 *  - **Ring tap when Service is dead** → [com.constellation.glass.halo.HaloTriggerReceiver]
 *    needs handling; tracked in P1.7 follow-up.
 */
class ConstellationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Debug builds only (Zack 2026-06-06): DebugTree does a stack-trace
        // lookup per log call to derive the tag, and the codebase logs heavily
        // on hot paths (state transitions, capture timing, every WSS frame). In
        // RELEASE we plant nothing — Timber.* calls become cheap no-ops (no tree
        // → no formatting, no logcat I/O), shaving CPU + latency on the glass.
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        Timber.i("ConstellationApp · onCreate · v${BuildConfig.VERSION_NAME} (${BuildConfig.PLATFORM})")
        // Service start is deferred to MainActivity / BootReceiver — see KDoc.
    }
}
