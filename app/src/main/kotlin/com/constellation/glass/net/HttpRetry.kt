package com.constellation.glass.net

import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Single-retry helper for the 5 one-shot HTTP clients we run against the
 * public Edge over Rokid Glasses WiFi.
 *
 * **Why this exists** (Q.4.5b): Rokid Glasses WiFi Wake-on-Wireless makes
 * the first TCP connect after deep-sleep take 15-20 seconds — past our
 * connectTimeout. OkHttp's connection pool can also hold "half-dead" sockets
 * that survive the WoW state transition but fail TLS handshake on reuse.
 * `WssClient` already handles this by calling `connectionPool.evictAll()`
 * before each reconnect attempt and looping; the 5 HTTP clients (Health,
 * Ping, Shortcuts, Fire, Auth) are one-shot and currently surface a 15s
 * timeout to the user as "Couldn't load…".
 *
 * Pattern: catch [SocketTimeoutException] or generic [IOException] with
 * timeout cause, evict the pool, retry **once**. Total max wait =
 * 2 × connectTimeout. If the second attempt still fails, we surface to
 * the caller — at that point the issue is real, not WoW.
 */

/**
 * Run [block], catching one transient timeout failure and retrying after
 * evicting the OkHttp connection pool. If both attempts fail, the second
 * failure is propagated.
 */
inline fun <T> httpRetryOnce(client: OkHttpClient, block: () -> T): T {
    return try {
        block()
    } catch (e: SocketTimeoutException) {
        Timber.i("HttpRetry · SocketTimeout, evicting pool and retrying once")
        client.connectionPool.evictAll()
        block()
    } catch (e: IOException) {
        // OkHttp wraps some timeout flavors as IOException with timeout cause
        // (e.g. "Connect call failed"). Match by message as a fallback.
        val msg = e.message.orEmpty().lowercase()
        if ("timeout" in msg || "timed out" in msg || "connect" in msg) {
            Timber.i("HttpRetry · IO/connect failure (${e.javaClass.simpleName}), evicting pool and retrying once")
            client.connectionPool.evictAll()
            block()
        } else {
            throw e
        }
    }
}
