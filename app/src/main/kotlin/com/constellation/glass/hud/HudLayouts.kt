package com.constellation.glass.hud

import com.constellation.glass.GestureBindings

/**
 * Pure-data HUD layout constants. No SDK dependency.
 *
 * In v2.0 this object also emitted CXR-L `customView` JSON. Per v2.1 pivot,
 * each platform renders its own view tree (glass: Compose Activity; phone
 * debug: WindowManager TextView overlay), so this file is now just shared
 * IDs and the single helper for the g-wave glyph row.
 */
object HudLayouts {

    // Stable IDs — used by both HUD surfaces so updateXxx can address the
    // correct sub-region without redrawing the world.
    const val ROOT_ID = "constellation_root"
    const val STATUS_ICON_ID = "status_icon"
    const val STATUS_TEXT_ID = "status_text"
    const val STATUS_META_ID = "status_meta"
    const val CARD_TITLE_ID = "card_title"
    const val CARD_BODY_ID = "card_body"
    const val CARD_SCROLL_ID = "card_scroll"
    const val CARD_FOOTER_ID = "card_footer"
    const val LISTENING_WAVE_ID = "listening_wave"
    const val LISTENING_PARTIAL_ID = "listening_partial"

    private const val GWAVE_CELLS = 21

    /**
     * Render a Gaussian-shaped horizontal bar of block glyphs whose height
     * encodes [amp] (0..1). Cells are widest near the center, falling off
     * toward the edges — gives the line a "wave" feel even though it's just
     * monospace characters.
     */
    fun gwave(amp: Float, cells: Int = GWAVE_CELLS): String {
        val center = cells / 2
        val glyphs = " ▁▂▃▄▅▆▇█"
        val sb = StringBuilder(cells)
        for (i in 0 until cells) {
            val d = kotlin.math.abs(i - center).toFloat() / center.toFloat()
            val h = (amp * (1f - d * d)).coerceIn(0f, 1f)
            val idx = (h * (glyphs.length - 1)).toInt().coerceIn(0, glyphs.length - 1)
            sb.append(glyphs[idx])
        }
        return sb.toString()
    }

    /** Footer hint for a CARD with the standard 3 options. Gesture labels follow
     *  the wearer's current [GestureBindings] (default: tap+up approve ·
     *  long-press modify · tap+down kill). */
    fun cardFooter(options: List<String>): String {
        val approve = GestureBindings.labelFor(GestureBindings.Action.APPROVE)
        val modify = GestureBindings.labelFor(GestureBindings.Action.MODIFY)
        val kill = GestureBindings.labelFor(GestureBindings.Action.KILL)
        if (options.isEmpty() || (options.size == 1 && options[0] == "approve")) {
            return "$approve approve · $modify modify · $kill kill"
        }
        // Question card: a single "answer" action (approve gesture → mic → speak
        // your reply). No dismiss — the wearer MUST answer.
        if (options.size == 1 && options[0].equals("answer", ignoreCase = true)) {
            return "$approve to answer"
        }
        return options.joinToString(" · ") { o ->
            when (o.lowercase()) {
                "approve" -> "$approve approve"
                "modify"  -> "$modify modify"
                "kill"    -> "$kill kill"
                "reject"  -> "$kill reject"
                "answer"  -> "$approve answer"
                else      -> o
            }
        }
    }
}
