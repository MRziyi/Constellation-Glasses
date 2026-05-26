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

        // Kick the foreground service. Boot path goes through BootReceiver;
        // first-install path goes through here so the service starts without
        // a reboot.
        ConstellationService.start(this)
    }
}
