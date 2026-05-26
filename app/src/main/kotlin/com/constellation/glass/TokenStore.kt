package com.constellation.glass

import android.content.Context

/**
 * Tiny key-value store for the Rokid CXR-L auth token. Kept simple
 * (SharedPreferences) so we don't pull in a crypto dep just for one string.
 * If the device is rooted or backups are enabled, a determined attacker
 * could read this — but on a personal wearable that's the user's threat
 * model, and the token can be revoked.
 */
object TokenStore {
    private const val PREFS = "constellation_secure"
    private const val KEY_TOKEN = "cxr_auth_token"

    fun read(ctx: Context): String? =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            ?.takeIf { it.isNotEmpty() }

    fun write(ctx: Context, token: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TOKEN, token).apply()
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TOKEN).apply()
    }
}
