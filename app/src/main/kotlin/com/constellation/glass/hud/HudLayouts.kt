package com.constellation.glass.hud

import org.json.JSONArray

/**
 * Per-state JSON builders for the right-eye HUD. Each function returns a
 * complete [SelfViewJson] tree ready for `customViewOpen`. Incremental
 * updates use [UpdateViewJson] (see [HudRenderer]).
 *
 * Layout convention (per GLASS-CLIENT-DESIGN.md §6 + ui-mockup.html §1.0):
 *   root LinearLayout gravity=top
 *   paddingTop=16dp, paddingStart/End=20dp
 *   paddingBottom=200dp  (leave room for the Halo Ring overlay)
 *   children stacked vertical, monochrome green
 *
 * Stable IDs are CRITICAL — updateCustomView diffs by id; missing ids
 * cause re-create + flicker. Per-state IDs are reused across renders.
 */
object HudLayouts {

    // ── ID constants — referenced from HudRenderer.update() ──────────────
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

    /** Render a Gaussian-shaped horizontal bar of block glyphs whose height
     *  encodes [amp] (0..1). Cells are widest near the center, falling off
     *  toward the edges — gives the line a "wave" feel even though it's just
     *  TextView characters. */
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

    private fun root(children: List<SelfViewJson>): SelfViewJson {
        val props = LinearLayoutProps().apply {
            id = ROOT_ID
            layout_width = "match_parent"
            layout_height = "match_parent"
            orientation = "vertical"
            paddingTop = "16dp"
            paddingStart = "20dp"
            paddingEnd = "20dp"
            paddingBottom = "200dp"     // leave ring pip region clear
            backgroundColor = "#050A06"  // near-black; system shows transparent
        }
        return SelfViewJson().apply {
            type = "LinearLayout"
            this.props = props.toJson()
            this.children = children.toMutableList()
        }
    }

    private fun textView(
        id: String,
        text: String,
        textSize: String = "18sp",
        textColor: String = "#00CC00",
        textStyle: String? = null,
        paddingTop: String? = null,
        paddingBottom: String? = null,
    ): SelfViewJson {
        val p = TextViewProps().apply {
            this.id = id
            this.layout_width = "match_parent"
            this.layout_height = "wrap_content"
            this.text = text
            this.textColor = textColor
            this.textSize = textSize
            textStyle?.let { this.textStyle = it }
            paddingTop?.let { this.paddingTop = it }
            paddingBottom?.let { this.paddingBottom = it }
        }
        return SelfViewJson().apply {
            type = "TextView"
            props = p.toJson()
        }
    }

    // ── State layouts ────────────────────────────────────────────────────

    /** LISTENING — mic open. Layout:
     *    🎤 listening…
     *    ░░░░░░░░░░░░░░░░░░░░░         ← g-wave (amplitude-driven)
     *    [partial transcript]          ← server-streamed partials
     *    say "完了" to send · 3s */
    fun listening(elapsedSec: Int = 0, amplitude: Float = 0f, partialText: String? = null): SelfViewJson =
        root(listOf(
            textView(
                id = STATUS_ICON_ID,
                text = "🎤 listening…",
                textSize = "26sp",
                textColor = "#00FF00",
                textStyle = "bold",
            ),
            textView(
                id = LISTENING_WAVE_ID,
                text = gwave(amplitude),
                textSize = "22sp",
                textColor = "#00FF66",
                paddingTop = "10dp",
            ),
            textView(
                id = LISTENING_PARTIAL_ID,
                text = partialText ?: "",
                textSize = "16sp",
                textColor = "#00CC00",
                paddingTop = "10dp",
            ),
            textView(
                id = STATUS_TEXT_ID,
                text = if (elapsedSec > 0) "say \"完了\" to send · ${elapsedSec}s" else "say \"完了\" to send",
                textSize = "14sp",
                textColor = "#008800",
                paddingTop = "8dp",
            ),
        ))

    /** THINKING — single replace-in-place row. */
    fun thinking(icon: String = "⌛", detail: String = "thinking…", meta: String? = null): SelfViewJson =
        root(buildList {
            add(textView(
                id = STATUS_ICON_ID,
                text = "$icon $detail",
                textSize = "20sp",
                textColor = "#00FF00",
                textStyle = "bold",
            ))
            meta?.takeIf { it.isNotEmpty() }?.let {
                add(textView(
                    id = STATUS_META_ID,
                    text = it,
                    textSize = "14sp",
                    textColor = "#008800",
                    paddingTop = "6dp",
                ))
            }
        })

    /** CARD — title + scrollable body + footer hint. */
    fun card(
        titleText: String,
        titleStyle: String? = "bold",
        bodyText: String,
        scrollPos: Int = 1,
        scrollTotal: Int = 1,
        footer: String = "好 approve · 改 modify · 停 kill",
    ): SelfViewJson = root(listOf(
        textView(
            id = CARD_TITLE_ID,
            text = titleText,
            textSize = "22sp",
            textColor = "#00FF00",
            textStyle = titleStyle,
        ),
        textView(
            id = CARD_BODY_ID,
            text = bodyText,
            textSize = "16sp",
            textColor = "#00CC00",
            paddingTop = "8dp",
        ),
        textView(
            id = CARD_SCROLL_ID,
            text = if (scrollTotal > 1) "▼ $scrollPos / $scrollTotal" else "",
            textSize = "12sp",
            textColor = "#008800",
            paddingTop = "6dp",
        ),
        textView(
            id = CARD_FOOTER_ID,
            text = footer,
            textSize = "12sp",
            textColor = "#008800",
            paddingTop = "10dp",
        ),
    ))

    /** INSIGHT — proactive push; ttl progress hint at the bottom. */
    fun insight(titleText: String, bodyText: String, ttlSec: Int = 8): SelfViewJson = root(listOf(
        textView(
            id = CARD_TITLE_ID,
            text = "✦ $titleText",
            textSize = "20sp",
            textColor = "#00FF00",
            textStyle = "bold",
        ),
        textView(
            id = CARD_BODY_ID,
            text = bodyText,
            textSize = "16sp",
            textColor = "#00CC00",
            paddingTop = "8dp",
        ),
        textView(
            id = CARD_FOOTER_ID,
            text = "看一下 engage · auto-close ${ttlSec}s",
            textSize = "12sp",
            textColor = "#008800",
            paddingTop = "10dp",
        ),
    ))

    /** OFFLINE — overlay error state. */
    fun offline(): SelfViewJson = root(listOf(
        textView(
            id = STATUS_ICON_ID,
            text = "● offline · reconnecting…",
            textSize = "20sp",
            textColor = "#FF7C7C",   // red — also downsampled but visibly different from green
            textStyle = "bold",
        ),
        textView(
            id = STATUS_TEXT_ID,
            text = "Cortex is unreachable. The HUD will return when the link is back.",
            textSize = "14sp",
            textColor = "#996666",
            paddingTop = "8dp",
        ),
    ))

    // ── Incremental update payloads ──────────────────────────────────────

    /** Single-row replace: change icon + detail text. */
    fun updateThinking(icon: String, detail: String, meta: String? = null): UpdateViewJson {
        val u = UpdateViewJson()
        u.updateList.add(UpdateViewJson.UpdateJson(STATUS_ICON_ID).apply {
            props["text"] = "$icon $detail"
        })
        if (meta != null) {
            u.updateList.add(UpdateViewJson.UpdateJson(STATUS_META_ID).apply {
                props["text"] = meta
            })
        }
        return u
    }

    /** Scroll within the current card — replace body text only. */
    fun updateCardBody(bodyText: String, scrollPos: Int, scrollTotal: Int): UpdateViewJson {
        val u = UpdateViewJson()
        u.updateList.add(UpdateViewJson.UpdateJson(CARD_BODY_ID).apply {
            props["text"] = bodyText
        })
        u.updateList.add(UpdateViewJson.UpdateJson(CARD_SCROLL_ID).apply {
            props["text"] = if (scrollTotal > 1) "▼ $scrollPos / $scrollTotal" else ""
        })
        return u
    }

    /** Listening update: elapsed tick + g-wave amplitude + partial transcript. */
    fun updateListeningElapsed(
        elapsedSec: Int,
        amplitude: Float = 0f,
        partialText: String? = null,
    ): UpdateViewJson {
        val u = UpdateViewJson()
        u.updateList.add(UpdateViewJson.UpdateJson(STATUS_TEXT_ID).apply {
            props["text"] = "say \"完了\" to send · ${elapsedSec}s"
        })
        u.updateList.add(UpdateViewJson.UpdateJson(LISTENING_WAVE_ID).apply {
            props["text"] = gwave(amplitude)
        })
        if (partialText != null) {
            u.updateList.add(UpdateViewJson.UpdateJson(LISTENING_PARTIAL_ID).apply {
                props["text"] = partialText
            })
        }
        return u
    }
}
