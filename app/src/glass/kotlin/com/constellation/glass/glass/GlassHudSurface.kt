package com.constellation.glass.glass

import android.content.Context
import android.content.Intent
import com.constellation.glass.glass.hud.GlassHudActivity
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.hud.HudTheme
import com.constellation.glass.hud.ScrollWindow
import com.constellation.glass.hud.StyledRunsRenderer
import com.constellation.glass.state.AppState
import org.json.JSONArray
import timber.log.Timber

/**
 * HudSurface implementation for the on-eyepiece [GlassHudActivity].
 *
 * The Activity owns view rendering; this class pushes state into
 * [GlassHudState] and decides when to bring the Activity to the foreground
 * (Idle → HUD-on transition).
 */
class GlassHudSurface(private val ctx: Context) : HudSurface {

    /** Active card body viewport. Null when no card is up. */
    private var scrollWindow: ScrollWindow? = null

    override fun transition(prev: AppState, next: AppState) {
        Timber.i("GlassHudSurface · $prev → $next")
        GlassHudState.update { copy(appState = next) }
        when (next) {
            AppState.Idle -> {
                // Don't actively close the Activity — let it stay paused so
                // re-foregrounding on next wake is instant. The Activity sees
                // appState=Idle and renders nothing (panel pixels off →
                // transparent).
            }
            AppState.Listening, AppState.Thinking, AppState.Card,
            AppState.Insight, AppState.Offline -> {
                bringActivityToFront()
            }
        }
    }

    override fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?) {
        GlassHudState.update {
            copy(icon = icon, detailRuns = detailRuns, metaRuns = metaRuns)
        }
    }

    override fun updateListening(elapsedSec: Int, amplitude: Float, partialRuns: JSONArray?) {
        GlassHudState.update {
            copy(
                listeningElapsedSec = elapsedSec,
                listeningAmplitude = amplitude,
                listeningPartialRuns = partialRuns ?: listeningPartialRuns,
            )
        }
    }

    override fun showCard(
        cardId: String,
        titleRuns: JSONArray?,
        bodyRuns: JSONArray?,
        options: List<String>,
    ) {
        val (flatBody, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(bodyRuns))
        val wrapped = ScrollWindow.wrap(flatBody, maxChars = HudTheme.cardBodyWrapChars)
        val window = ScrollWindow(wrapped, windowSize = HudTheme.cardBodyVisibleLines)
        scrollWindow = window
        GlassHudState.update {
            copy(
                cardId = cardId,
                cardTitleRuns = titleRuns,
                cardBodyText = window.windowText(),
                cardScrollPos = if (window.totalWindows() > 1) window.position() else 0,
                cardScrollTotal = if (window.totalWindows() > 1) window.totalWindows() else 0,
                cardOptions = options,
            )
        }
    }

    override fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int) {
        GlassHudState.update {
            copy(
                insightTitleRuns = titleRuns,
                insightBodyRuns = bodyRuns,
                insightTtlSec = ttlSec,
            )
        }
    }

    override fun scrollCardUp(): Boolean {
        val w = scrollWindow ?: return false
        if (!w.scrollUp()) return false
        GlassHudState.update {
            copy(cardBodyText = w.windowText(), cardScrollPos = w.position())
        }
        return true
    }

    override fun scrollCardDown(): Boolean {
        val w = scrollWindow ?: return false
        if (!w.scrollDown()) return false
        GlassHudState.update {
            copy(cardBodyText = w.windowText(), cardScrollPos = w.position())
        }
        return true
    }

    override fun destroy() {
        // Activity lifecycle is independent — system handles teardown.
    }

    private fun bringActivityToFront() {
        try {
            val intent = Intent(ctx, GlassHudActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Timber.w(t, "GlassHudSurface · failed to start GlassHudActivity")
        }
    }
}
