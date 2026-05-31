package com.constellation.glass.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Runtime-editable Cortex WSS endpoint URL.
 *
 * **Source of truth = the pairing QR** (Zack 2026-05-31): the endpoint is no
 * longer baked into `BuildConfig`. The web console's QR carries
 * {endpoint, cookie_name, cookie_value}; scanning it writes the endpoint here
 * (alongside the cookie). The in-app Edit Endpoint screen can still override it
 * manually for debugging; [WssClient] picks up changes on reconnect.
 *
 * **Default value** is the empty string — an unpaired device has NO endpoint.
 * That surfaces as the AuthExpired HUD (WssClient treats a blank URL as
 * "needs pairing"), prompting a scan. No hard-coded server anywhere.
 *
 * **Format expected**: full `wss://host[:port]/path` URL. We do minimal
 * validation (must start with `wss://` or `ws://`); structural problems are
 * surfaced by OkHttp at connect time.
 */
val Context.appPrefs by preferencesDataStore(name = "constellation_app_prefs")

object EndpointStore {

    private val KEY_ENDPOINT = stringPreferencesKey("cortex_endpoint")

    /** Stream of the current endpoint. Empty string when unpaired (no scan yet). */
    fun flow(ctx: Context): Flow<String> = ctx.appPrefs.data.map { prefs ->
        prefs[KEY_ENDPOINT] ?: ""
    }

    suspend fun read(ctx: Context): String {
        val prefs = ctx.appPrefs.data
        var current = ""
        prefs.collect { p ->
            current = p[KEY_ENDPOINT] ?: ""
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
