package com.constellation.glass.auth

import android.content.Context

/**
 * Persists the `console_session` cookie value across process restarts.
 * The Console edge issues this cookie on POST /api/auth/login; 30-day TTL by
 * default (see Constellation-Console/edge/.../config.py).
 *
 * Glass keeps it plain-text in SharedPreferences. Same threat model as
 * TokenStore — on a personal wearable, full-disk encryption + lock-screen
 * cover this; we don't need crypto here.
 */
object CookieStore {
    private const val PREFS = "constellation_secure"
    private const val KEY_COOKIE_NAME = "edge_cookie_name"
    private const val KEY_COOKIE_VALUE = "edge_cookie_value"

    data class Cookie(val name: String, val value: String) {
        /** As used in HTTP `Cookie:` request header. */
        fun toHeader(): String = "$name=$value"
    }

    fun read(ctx: Context): Cookie? {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val n = p.getString(KEY_COOKIE_NAME, null) ?: return null
        val v = p.getString(KEY_COOKIE_VALUE, null) ?: return null
        if (n.isEmpty() || v.isEmpty()) return null
        return Cookie(n, v)
    }

    fun write(ctx: Context, name: String, value: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COOKIE_NAME, name).putString(KEY_COOKIE_VALUE, value).apply()
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_COOKIE_NAME).remove(KEY_COOKIE_VALUE).apply()
    }
}
