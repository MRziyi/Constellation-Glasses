package com.constellation.glass

import android.app.Application
import timber.log.Timber

/**
 * App entry point. The app has no Activity — everything happens in
 * [ConstellationService]. This class just sets up Timber logging.
 */
class ConstellationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Timber.i("ConstellationApp · onCreate · v${BuildConfig.VERSION_NAME}")

        // If we already have a token from a prior install/auth, start the
        // service so the HUD is alive after reboot without the wearer
        // opening MainActivity. First-time setup goes through MainActivity →
        // auth → token persisted → service started from there.
        if (TokenStore.read(this) != null) {
            Timber.i("ConstellationApp · token present, starting service")
            ConstellationService.start(this)
        } else {
            Timber.i("ConstellationApp · no token, deferring service start to MainActivity")
        }
    }
}
