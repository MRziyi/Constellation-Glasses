package com.constellation.glass.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.constellation.glass.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Runtime-editable Cortex WSS endpoint URL.
 *
 * **Why DataStore instead of `BuildConfig.WSS_URL`** (P-app.A, D5):
 * P1.6 baked the endpoint into `BuildConfig` at compile time. That's fine for
 * shipping but means swapping endpoint (test droplet → prod, ngrok tunnel,
 * etc.) requires a rebuild. DataStore lets the user edit it from the
 * in-app Connect screen and the [WssClient] picks up the change without
 * an APK reinstall.
 *
 * **Default value** is still `BuildConfig.WSS_URL` — so an existing install
 * with no DataStore entry yet (fresh first-launch, or upgrade from pre-DataStore
 * APK) reads the build default. Once the user saves anything, that overrides.
 *
 * **Format expected**: full `wss://host[:port]/path` URL. We do minimal
 * validation (must start with `wss://` or `ws://`); structural problems are
 * surfaced by OkHttp at connect time.
 */
val Context.appPrefs by preferencesDataStore(name = "constellation_app_prefs")

object EndpointStore {

    private val KEY_ENDPOINT = stringPreferencesKey("cortex_endpoint")

    /** Stream of the current endpoint. Emits the BuildConfig default if unset. */
    fun flow(ctx: Context): Flow<String> = ctx.appPrefs.data.map { prefs ->
        prefs[KEY_ENDPOINT] ?: BuildConfig.WSS_URL
    }

    suspend fun read(ctx: Context): String {
        val prefs = ctx.appPrefs.data
        var current: String = BuildConfig.WSS_URL
        prefs.collect { p ->
            current = p[KEY_ENDPOINT] ?: BuildConfig.WSS_URL
            return@collect  // first emission only
        }
        return current
    }

    suspend fun write(ctx: Context, url: String) {
        ctx.appPrefs.edit { it[KEY_ENDPOINT] = url.trim() }
    }

    /** Light syntactic check — full validation happens at OkHttp connect time. */
    fun looksValid(url: String): Boolean {
        val u = url.trim()
        return (u.startsWith("wss://") || u.startsWith("ws://")) && u.length > 8
    }
}
