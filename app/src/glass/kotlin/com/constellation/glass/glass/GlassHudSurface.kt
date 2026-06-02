package com.constellation.glass.glass

import android.content.Context
import com.constellation.glass.hud.CardScrollBus
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.hud.StyledRunsRenderer
import com.constellation.glass.state.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
     * Event-driven foreground tracking (Zack 2026-06-02): collect MainActivity's
     * foreground StateFlow instead of polling `isForeground` at 1Hz forever (that
     * was 86400 idle main-thread wakeups/day for nothing). SYSTEM_ALERT_WINDOW
     * sits above the in-app settings UI, so while settings is foreground we detach
     * the overlay; on leaving it we re-attach. Same semantics as the old poll,
     * minus the periodic tick.
     */
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastSeenForeground = false

    init {
        scope.launch {
            com.constellation.glass.MainActivity.foreground.collect { fg ->
                if (fg != lastSeenForeground) {
                    lastSeenForeground = fg
                    if (fg) overlay.detach() else overlay.attach()
                }
            }
        }
    }

    override fun transition(prev: AppState, next: AppState) {
        Timber.i("GlassHudSurface · $prev → $next")
        GlassHudState.update { copy(appState = next) }
        when (next) {
            AppState.Idle -> {
                // Clear transient status on end-of-turn so the NEXT task doesn't
                // briefly show this task's stale detail before its first progress
                // arrives (Zack 2026-06-01 — the only other source of the "…"/stale
                // Thinking glimpse besides the very first turn).
                GlassHudState.update {
                    copy(detailRuns = null, icon = "", metaRuns = null,
                         satelliteVisible = false, satelliteRuns = null)
                }
                // 2026-05-28 fix: on Idle, **fully detach the overlay window**
                // — don't just leave it attached with an empty Compose tree.
                // The previous "leave attached for instant next-attach" model
                // left visual residue on the panel (last-drawn frame stays
                // latched while WakeLock release fades the screen, and a
                // mid-scrolled body would show partially-clipped).
                // Real detach = WindowManager.removeView, panel framebuffer
                // composites without us. Re-attach on next non-Idle.
                overlay.wakeOff()
                overlay.detach()
            }
            AppState.Listening, AppState.Thinking, AppState.Card,
            AppState.Insight, AppState.Offline, AppState.AuthExpired -> {
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
            // White-box (C-71, Zack 2026-06-01): NEVER blank the status to a vague
            // fallback ("Working…"). If a hud_state arrives without detail (a
            // state-change frame, or a brief gap before the next specific
            // progress), KEEP the last real detail + icon so the wearer always
            // sees the actual last action — every internal state stays white-box.
            val keptDetail = if (detailRuns != null && detailRuns.length() > 0) detailRuns else this.detailRuns
            val keptIcon = if (icon.isNotEmpty()) icon else this.icon
            copy(icon = keptIcon, detailRuns = keptDetail, metaRuns = metaRuns)
        }
    }

    override fun updateSatellite(visible: Boolean, icon: String, detailRuns: JSONArray?) {
        GlassHudState.update {
            copy(satelliteVisible = visible, satelliteIcon = icon, satelliteRuns = detailRuns)
        }
    }

    override fun clearListeningPartial() {
        GlassHudState.update { copy(listeningPartialRuns = null) }
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
        echoRuns: JSONArray?,
        source: String,
    ) {
        // F1 (2026-05-28): store the full body unwrapped. CardHud Composable
        // wraps naturally by container width via Text(softWrap=true) inside
        // Modifier.verticalScroll(). External 2F SWIPE input is plumbed
        // through CardScrollBus.
        val (flatBody, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(bodyRuns))
        GlassHudState.update {
            copy(
                cardId = cardId,
                cardEchoRuns = echoRuns,
                cardTitleRuns = titleRuns,
                cardBodyText = flatBody,
                cardScrollPos = 0,       // legacy field, no longer used
                cardScrollTotal = 0,     // legacy field, no longer used
                cardOptions = options,
                cardSource = source,
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
        scope.cancel()
        overlay.destroy()
    }
}
