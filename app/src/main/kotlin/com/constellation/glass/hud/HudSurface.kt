package com.constellation.glass.hud

import com.constellation.glass.state.AppState
import org.json.JSONArray
import timber.log.Timber

/**
 * Abstraction over the actual HUD render surface. Two implementations:
 *  - [HudRenderer] — real CXR-L CustomView backed
 *  - [HeadlessHudSurface] — logs to Timber, used when DEV_HEADLESS or on
 *    devices without the Rokid Sprite service installed
 *
 * Both implement the same method surface so StateMachine doesn't need to
 * know which it's talking to.
 */
interface HudSurface {
    fun transition(prev: AppState, next: AppState)
    fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?)
    /** Update the LISTENING HUD. [amplitude] in 0..1 drives the g-wave gain;
     *  [partialRuns] (Level 2 server-side streaming transcript) renders the
     *  in-flight text below the wave. Either argument may be null to leave
     *  the corresponding sub-region unchanged. */
    fun updateListening(elapsedSec: Int, amplitude: Float = 0f, partialRuns: JSONArray? = null)
    fun showCard(cardId: String, titleRuns: JSONArray?, bodyRuns: JSONArray?, options: List<String>)
    fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int = 8)
    fun scrollCardUp(): Boolean
    fun scrollCardDown(): Boolean
}


/** Non-Rokid stub for dev builds. Prints what would be drawn to logcat. */
class HeadlessHudSurface : HudSurface {

    override fun transition(prev: AppState, next: AppState) {
        Timber.i("[HeadlessHUD] transition $prev → $next")
    }

    override fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?) {
        Timber.i("[HeadlessHUD] THINKING icon=$icon detail=${flatten(detailRuns)}  meta=${flatten(metaRuns)}")
    }

    override fun updateListening(elapsedSec: Int, amplitude: Float, partialRuns: JSONArray?) {
        val partial = flatten(partialRuns)
        val ampPct = (amplitude * 100).toInt()
        Timber.i("[HeadlessHUD] LISTENING ${elapsedSec}s amp=${ampPct}% partial=$partial")
    }

    override fun showCard(cardId: String, titleRuns: JSONArray?, bodyRuns: JSONArray?, options: List<String>) {
        Timber.i("[HeadlessHUD] CARD #$cardId  title=${flatten(titleRuns)}")
        Timber.i("[HeadlessHUD]            body=${flatten(bodyRuns)}")
        Timber.i("[HeadlessHUD]            options=$options")
    }

    override fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int) {
        Timber.i("[HeadlessHUD] INSIGHT title=${flatten(titleRuns)} body=${flatten(bodyRuns)} ttl=${ttlSec}s")
    }

    override fun scrollCardUp(): Boolean {
        Timber.i("[HeadlessHUD] scrollCardUp"); return true
    }

    override fun scrollCardDown(): Boolean {
        Timber.i("[HeadlessHUD] scrollCardDown"); return true
    }

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
