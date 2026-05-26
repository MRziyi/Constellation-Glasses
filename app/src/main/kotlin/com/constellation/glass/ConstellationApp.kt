package com.constellation.glass

import android.app.Application
import com.constellation.glass.auth.CookieStore
import timber.log.Timber

/**
 * App entry point. Plants Timber and — if we already have an edge cookie
 * from a previous login — starts [ConstellationService] right away so the
 * HUD wakes after device reboot without the wearer opening the launcher.
 *
 * First-time setup: cookie missing → [MainActivity] prompts for password →
 * stores cookie → starts service.
 */
class ConstellationApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
        Timber.i("ConstellationApp · onCreate · v${BuildConfig.VERSION_NAME} (${BuildConfig.PLATFORM})")

        if (CookieStore.read(this) != null) {
            Timber.i("ConstellationApp · cookie present, starting service")
            ConstellationService.start(this)
        } else {
            Timber.i("ConstellationApp · no cookie, waiting for MainActivity login")
        }
    }
}
