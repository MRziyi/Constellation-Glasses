package com.constellation.glass.hud

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized HUD layout / styling constants for the Rokid Glasses 480×640
 * monochrome-green panel.
 *
 * All sizing values are **estimates** until real-device P1.5 calibration. After
 * the first physical R08 deploy we log `Resources.getDisplayMetrics().density`
 * and `densityDpi`, then tune the values here in one place. Do NOT inline these
 * elsewhere — keeping them in one object is the whole point.
 */
object HudTheme {

    // ── Panel (Rokid Glasses) ────────────────────────────────────────────────
    val panelWidthDp = 480.dp
    val panelHeightDp = 640.dp

    // ── Colors (monochrome green) ────────────────────────────────────────────
    /** Primary foreground. JBD4020 is single-color (green) — alpha is the only knob. */
    val fg = Color(0xFF50FF50)
    /** Dimmed foreground for prose 50% alpha. Used for {style:"dim"} runs and meta lines. */
    val fgDim = Color(0x8050FF50)
    /** Warning amber — last 5s of TTL countdown, error overlay accent. */
    val fgError = Color(0xFFFFA040)
    /** HUD background — fully transparent so the rest of the world shows through. */
    val bg = Color.Transparent

    // ── Type scale (P1.5 真机校准前的估值; tune after first device deploy) ──────
    val titleSize = 22.sp
    val bodySize = 16.sp
    val metaSize = 13.sp
    val footerSize = 12.sp

    // ── Layout ───────────────────────────────────────────────────────────────
    /** Left + right gutter. */
    val sidePadding = 20.dp
    /** Top of the HUD area (status pill space). */
    val topPadding = 16.dp
    /**
     * Reserved bottom strip — leaves room for the Halo Ring pip + system
     * indicators that other Rokid components draw in that zone. We don't draw
     * here; we leave it visually clear.
     *
     * Tightened from 200.dp → 120.dp on 2026-05-26 EOD: 200 left too much
     * unused vertical room on the simulator (and likely on the real panel);
     * the ring pip + any system indicators don't need that much. P1.5 will
     * confirm the actual safe bottom on real Rokid Glasses.
     */
    val bottomReservedDp = 120.dp

    // ── Card body wrapping ───────────────────────────────────────────────────
    /**
     * Max characters per line in CARD body before pre-wrap. The body text is
     * hard-wrapped by `ScrollWindow.wrap()` BEFORE Compose ever sees it, then
     * Compose renders the wrapped lines verbatim — so this constant directly
     * controls the visual line length regardless of container width.
     *
     * Bumped 28 → 42 on 2026-05-26 EOD because 28 produced cramped 4–5-word
     * lines on the OnePlus 9 simulator (and likely on the real panel too).
     * 42 is still conservative — the actual maximum at 16sp sans-serif on a
     * 480px panel is closer to 50, but we leave headroom for Chinese (which
     * is full-width) and for P1.5 real-device tuning.
     *
     * Future: replace pre-wrap with natural Compose text wrap + ScrollWindow
     * working at paragraph granularity. For now char-wrap is the simpler model.
     */
    const val cardBodyWrapChars = 42
    /** Number of body lines visible at once inside ScrollWindow. */
    const val cardBodyVisibleLines = 6

    // ── g-wave (Listening visualization) ─────────────────────────────────────
    /** Number of cells in the listening amplitude bar. */
    const val gwaveCells = 21
}
