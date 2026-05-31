package com.constellation.glass.hud

import com.constellation.glass.state.AppState
import org.json.JSONArray

/**
 * The single observable snapshot of what the HUD should render right now.
 *
 * **Why this lives in `main/` (not `glass/`)**: per C-40, shared/`main/` code
 * must not depend on flavor-specific code, but flavor code may use `main/`
 * types. Pre-P1.6 the Snapshot data class was nested in `GlassHudState`
 * (glass flavor only); the Compose renderer in `main/` couldn't read it.
 * Lifting the data class here lets both `glass` (GlassHudActivity) and
 * `phoneDebug` (PhoneDebugHudSurface simulator) render from the same shape.
 *
 * Each flavor still owns its own [StateFlow] holder (GlassHudState in glass/;
 * a parallel holder in phoneDebug/), but they emit the same data class.
 */
data class HudSnapshot(
    val appState: AppState = AppState.Idle,
    val icon: String = "",
    val detailRuns: JSONArray? = null,
    val metaRuns: JSONArray? = null,
    val cardId: String? = null,
    /**
     * Optional "echo" of what the wearer said (or the trigger context), shown
     * as a dim quoted line above the title. Drives the "quote + body" layout
     * (Zack 2026-05-30) so every card reads as a reply, not a bare statement.
     * null / empty → no quote row.
     */
    val cardEchoRuns: JSONArray? = null,
    val cardTitleRuns: JSONArray? = null,
    /**
     * Pre-wrapped, scroll-windowed body text. ScrollWindow logic lives in
     * GlassHudSurface (glass) / equivalent in phoneDebug; this field is the
     * already-visible slice.
     */
    val cardBodyText: String = "",
    /** 1-based page index for the scroll indicator. 0 means no scroll. */
    val cardScrollPos: Int = 0,
    val cardScrollTotal: Int = 0,
    val cardOptions: List<String> = emptyList(),
    /** Card source label (Zack 2026-05-31): Whisper / GPT / GPT Vision /
     *  Claude / Claude Vision / Cortex. Rendered bottom-right on the card. */
    val cardSource: String = "",
    val insightTitleRuns: JSONArray? = null,
    val insightBodyRuns: JSONArray? = null,
    val insightTtlSec: Int = 8,
    val listeningElapsedSec: Int = 0,
    val listeningAmplitude: Float = 0f,
    val listeningPartialRuns: JSONArray? = null,
)
