package com.constellation.glass.glass

import android.content.Context
import com.constellation.glass.hud.CardScrollBus
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.hud.StyledRunsRenderer
import com.constellation.glass.state.AppState
import org.json.JSONArray
import timber.log.Timber

/**
 * On-eyepiece HUD surface backed by a SYSTEM_ALERT_WINDOW overlay
 * ([GlassHudOverlay]).
 *
 * **2026-05-28 pivot** — replaces the previous "fullscreen transparent
 * Activity" model (deleted [com.constellation.glass.glass.hud.GlassHudActivity]).
 * The overlay is a real floating window above all apps/launcher; the panel
 * below stays visible. Wake-on-update so the panel lights up when a
 * transition arrives during auto-lock.
 *
 * Mirrors the architecture used by [com.constellation.glass.phonedebug.PhoneDebugHudSurface]
 * for the simulator; both flavors now share the SYSTEM_ALERT_WINDOW + Compose
 * pattern. The render layer (composables, theme, snapshot data class) lives
 * in `main/`; this file only handles the per-flavor host plumbing
 * (state push + overlay lifecycle).
 */
class GlassHudSurface(private val ctx: Context) : HudSurface {

    /** Lazy-created on first transition out of Idle. */
    private val overlay = GlassHudOverlay(ctx)

    /**
     * Same pattern phoneDebug uses: poll `MainActivity.isForeground` at 1Hz
     * and detach/reattach the overlay accordingly. SYSTEM_ALERT_WINDOW sits
     * above the in-app settings UI, so during settings we let go of the
     * panel space.
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var foregroundWatcher: Runnable? = null
    private var lastSeenForeground = false

    init {
        main.post { startForegroundWatcher() }
    }

    private fun startForegroundWatcher() {
        val tick = object : Runnable {
            override fun run() {
                val fg = com.constellation.glass.MainActivity.isForeground.get()
                if (fg != lastSeenForeground) {
                    lastSeenForeground = fg
                    if (fg) overlay.detach() else overlay.attach()
                }
                main.postDelayed(this, 1000L)
            }
        }
        foregroundWatcher = tick
        main.post(tick)
    }

    override fun transition(prev: AppState, next: AppState) {
        Timber.i("GlassHudSurface · $prev → $next")
        GlassHudState.update { copy(appState = next) }
        when (next) {
            AppState.Idle -> {
                // Compose tree collapses to empty for Idle; the overlay window
                // stays attached at 0×0 so the next transition is instant.
                // Release the wake lock so the panel can auto-lock normally.
                overlay.wakeOff()
            }
            AppState.Listening, AppState.Thinking, AppState.Card,
            AppState.Insight, AppState.Offline -> {
                // Don't fight the in-app settings UI for the panel.
                if (com.constellation.glass.MainActivity.isForeground.get()) {
                    Timber.i("GlassHudSurface · MainActivity foreground, snapshot updated but overlay hidden")
                    return
                }
                overlay.attach()
                // Keep the panel awake for the entire duration of the HUD
                // (cards have 30s+ TTLs; brief 15s pulses would let the panel
                // lock mid-view). Released on transition → Idle.
                overlay.wakeOn()
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
        // F1 (2026-05-28): store the full body unwrapped. CardHud Composable
        // wraps naturally by container width via Text(softWrap=true) inside
        // Modifier.verticalScroll(). External 2F SWIPE input is plumbed
        // through CardScrollBus.
        val (flatBody, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(bodyRuns))
        GlassHudState.update {
            copy(
                cardId = cardId,
                cardTitleRuns = titleRuns,
                cardBodyText = flatBody,
                cardScrollPos = 0,       // legacy field, no longer used
                cardScrollTotal = 0,     // legacy field, no longer used
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

    // F1: scroll commands are pushed to CardScrollBus; the actual scrollState
    // is owned by the CardHud Composable which subscribes + animateScrollBy.
    override fun scrollCardUp(): Boolean {
        CardScrollBus.emit(CardScrollBus.ScrollCmd.Up)
        return true
    }

    override fun scrollCardDown(): Boolean {
        CardScrollBus.emit(CardScrollBus.ScrollCmd.Down)
        return true
    }

    override fun destroy() {
        foregroundWatcher?.let { main.removeCallbacks(it) }
        foregroundWatcher = null
        overlay.destroy()
    }
}
