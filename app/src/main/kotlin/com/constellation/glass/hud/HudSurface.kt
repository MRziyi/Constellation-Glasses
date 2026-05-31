package com.constellation.glass.hud

import com.constellation.glass.state.AppState
import org.json.JSONArray
import timber.log.Timber

/**
 * Abstraction over the actual HUD render surface. Two real implementations:
 *  - `GlassHudSurface` (glass flavor) — drives the on-eyepiece Compose
 *    Activity for the right-eye 480×640 panel.
 *  - `PhoneDebugHudSurface` (phoneDebug flavor) — SYSTEM_ALERT_WINDOW overlay
 *    on a regular Android phone for protocol verification.
 *
 * Plus [LoggingHudSurface] in core: pure logcat output, used as a last-resort
 * fallback when the platform's primary surface is unavailable (e.g. overlay
 * permission denied).
 *
 * Both flavors implement the same method surface so StateMachine doesn't need
 * to know which it's talking to.
 */
interface HudSurface {
    fun transition(prev: AppState, next: AppState)
    fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?)
    /** Update the LISTENING HUD. [amplitude] in 0..1 drives the g-wave gain;
     *  [partialRuns] (Level 2 server-side streaming transcript) renders the
     *  in-flight text below the wave. Either argument may be null to leave
     *  the corresponding sub-region unchanged. */
    fun updateListening(elapsedSec: Int, amplitude: Float = 0f, partialRuns: JSONArray? = null)
    fun showCard(
        cardId: String,
        titleRuns: JSONArray?,
        bodyRuns: JSONArray?,
        options: List<String>,
        echoRuns: JSONArray? = null,
        source: String = "",
    )
    fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int = 8)
    fun scrollCardUp(): Boolean
    fun scrollCardDown(): Boolean
    /** Called by the service when shutting down. Free any windows / activities. */
    fun destroy() {}
}

/** Logcat-only fallback. Always usable; rendered to Timber. */
class LoggingHudSurface : HudSurface {

    override fun transition(prev: AppState, next: AppState) {
        Timber.i("[hud] transition $prev → $next")
    }

    override fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?) {
        Timber.i("[hud] THINKING icon=$icon detail=${flatten(detailRuns)} meta=${flatten(metaRuns)}")
    }

    override fun updateListening(elapsedSec: Int, amplitude: Float, partialRuns: JSONArray?) {
        val ampPct = (amplitude * 100).toInt()
        Timber.i("[hud] LISTENING ${elapsedSec}s amp=${ampPct}% partial=${flatten(partialRuns)}")
    }

    override fun showCard(
        cardId: String,
        titleRuns: JSONArray?,
        bodyRuns: JSONArray?,
        options: List<String>,
        echoRuns: JSONArray?,
        source: String,
    ) {
        Timber.i("[hud] CARD #$cardId echo=${flatten(echoRuns)} title=${flatten(titleRuns)} source=$source")
        Timber.i("[hud]      body=${flatten(bodyRuns)}")
        Timber.i("[hud]      options=$options")
    }

    override fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int) {
        Timber.i("[hud] INSIGHT title=${flatten(titleRuns)} body=${flatten(bodyRuns)} ttl=${ttlSec}s")
    }

    override fun scrollCardUp(): Boolean { Timber.i("[hud] scrollCardUp"); return true }
    override fun scrollCardDown(): Boolean { Timber.i("[hud] scrollCardDown"); return true }

    private fun flatten(arr: JSONArray?): String {
        if (arr == null) return "(null)"
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(o.optString("text", ""))
        }
        return "\"$sb\""
    }
}
