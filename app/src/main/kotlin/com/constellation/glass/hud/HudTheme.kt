package com.constellation.glass.hud

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized HUD layout / styling constants for the Rokid Glasses panel.
 *
 * History:
 *   - Initial values (titleSize=22sp etc.) were OnePlus-9 simulator estimates.
 *   - 2026-05-26 EOD: cardBodyWrapChars 28 → 42, bottomReservedDp 200 → 120
 *     based on simulator visuals.
 *   - 2026-05-27 EOD: measured `densityDpi = 240 (hdpi)` on the real eyewear.
 *   - **2026-05-28**: real-device feedback "界面比预想大很多" → ~30% scale-down
 *     across the type scale; tightened paddings; dropped bottomReservedDp
 *     (HUD is now an overlay window, not a fullscreen Activity, so reserving
 *     "bottom space for the ring pip" inside our own draw area is no longer
 *     a thing — overlay sits where we place it on the panel).
 *
 * Do NOT inline these elsewhere — keeping them in one object is the whole point.
 */
object HudTheme {

    // ── Panel (Rokid Glasses) ────────────────────────────────────────────────
    /**
     * Physical panel size (px). Real content `dp` size at density 240 (hdpi)
     * = panel_px / 1.5 = 320 dp wide × 426 dp tall.
     */
    val panelWidthDp = 480.dp
    val panelHeightDp = 640.dp

    // ── Colors (monochrome green) ────────────────────────────────────────────
    /** Primary foreground. JBD4020 is single-color (green) — alpha is the only knob. */
    val fg = Color(0xFF50FF50)
    /** Dimmed foreground for prose 50% alpha. Used for {style:"dim"} runs and meta lines. */
    val fgDim = Color(0x8050FF50)
    /** Warning amber — last 5s of TTL countdown, error overlay accent. */
    val fgError = Color(0xFFFFA040)

    // ── Card frame (Phase C — actual card visual, not just transparent bg) ──
    /**
     * Card background: near-black with high alpha so the green text reads on
     * any AR backdrop. On the eyewear's monochrome-green panel, only the lit
     * pixels (border + text) are visible to the wearer; the dark fill is
     * effectively transparent (unlit pixels pass AR-through). On the
     * phoneDebug simulator the dark fill is what makes the card visually
     * distinct from the rest of the phone UI.
     */
    val cardBg = Color(0xE6000000)
    /** Card border — faint dim green stroke, monochrome-green-panel friendly. */
    val cardBorder = Color(0x80_50_FF_50)
    val cardCornerRadius = 12.dp
    val cardShape = RoundedCornerShape(12.dp)
    /** Card content max width (panel is 320dp wide @ 240dpi; leave breathing room). */
    val cardMaxWidthDp = 300.dp
    /** Card content max height (panel is 426dp tall; cap to fit). */
    val cardMaxHeightDp = 380.dp
    /** Card inner padding (around the actual content). */
    val cardInnerPadding = 12.dp

    // ── Type scale (post real-device 2026-05-28 feedback) ────────────────────
    val titleSize = 14.sp
    val bodySize = 11.sp
    val metaSize = 10.sp
    val footerSize = 9.sp

    // ── Layout (overlay-internal) ────────────────────────────────────────────
    /** Tight side gutter inside the card (Phase A reduction from 20dp → 10dp). */
    val sidePadding = 10.dp
    /** Top gutter inside the card (Phase A reduction from 16dp → 8dp). */
    val topPadding = 8.dp

    // ── Card body scroll (F1 redesign 2026-05-28) ────────────────────────────
    /**
     * Pre-F1 (`cardBodyWrapChars` + `cardBodyVisibleLines`) hard-wrapped the body
     * at a fixed char count and paginated it into N-line windows BEFORE Compose
     * saw the text. That produced "lines too narrow" + "wrong page math" on the
     * real eyewear (different font metrics than the simulator).
     *
     * F1 model: the body string is passed unwrapped to Compose `BasicText`,
     * which wraps naturally by container width. The body Composable is wrapped
     * in `verticalScroll(rememberScrollState())` bounded by
     * `cardBodyMaxHeightDp`. External 2F SWIPE input is plumbed through
     * `CardScrollBus`; the Composable animates the scrollState.
     */
    /** Max visible body height inside the card before scrolling kicks in. */
    val cardBodyMaxHeightDp = 240.dp
    /**
     * Pixels per 2F swipe gesture. Roughly equivalent to "about 3 lines at
     * bodySize=11sp on density 240 → ~50 px × 3 ≈ 150 px". Empirical; tune.
     */
    val cardScrollPxPerSwipe = 150f

    // ── g-wave (Listening visualization) ─────────────────────────────────────
    /** Number of cells in the listening amplitude bar. */
    const val gwaveCells = 21
}
