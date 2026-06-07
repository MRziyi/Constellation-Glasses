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
        if (next == AppState.Idle) {
            // Slide the CURRENT card out, THEN blank the snapshot. The Idle state
            // renders nothing (IdleHud is empty), so flipping the snapshot to Idle
            // up-front would make the card vanish by recomposition before the
            // slide-up is visible (Zack 2026-06-02 — "looks like it just
            // disappears"). Deferring the blank to detach's completion keeps the
            // card rendered for the whole slide. The blank also clears transient
            // status so the NEXT task doesn't flash this one's stale detail.
            // Keep the panel LIT through the whole slide — release the wake lock
            // only after it finishes. Releasing it mid-animation lets YodaOS start
            // its auto-dim/refresh and drops frames, which reads as a downward
            // judder (Zack 2026-06-02).
            overlay.detach {
                overlay.wakeOff()
                GlassHudState.update {
                    copy(appState = AppState.Idle, detailRuns = null, icon = "",
                         metaRuns = null, satelliteVisible = false, satelliteRuns = null)
                }
            }
            return
        }
        GlassHudState.update { copy(appState = next) }
        // Non-Idle: show the HUD. Don't fight the in-app settings UI for the panel.
        if (com.constellation.glass.MainActivity.isForeground.get()) {
            Timber.i("GlassHudSurface · MainActivity foreground, snapshot updated but overlay hidden")
            return
        }
        overlay.attach()
        // Keep the panel awake for the entire duration of the HUD (cards have 30s+
        // TTLs; brief 15s pulses would let the panel lock mid-view). Released → Idle.
        overlay.wakeOn()
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
        continuable: Boolean,
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
                cardContinuable = continuable,
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
