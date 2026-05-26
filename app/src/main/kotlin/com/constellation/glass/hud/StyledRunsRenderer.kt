package com.constellation.glass.hud

import org.json.JSONArray

/**
 * Parses Cortex-format styled-runs (`[{"text":..., "style":"bold"|...}]`) into
 * platform-neutral data. v2.0 also emitted CXR-L `SelfViewJson` here; that
 * codepath is gone (we no longer ship through customView JSON). Each platform
 * (`glass` Compose, `phoneDebug` TextView overlay) consumes the parsed
 * [StyledRun] list and renders as it pleases.
 */
object StyledRunsRenderer {

    /**
     * Single styled segment. [style] is the original Cortex tag — the renderer
     * decides what to do with it (bold weight, dim color, code-tinted background,
     * etc.). Keep the raw tag rather than baking visual decisions in.
     */
    data class StyledRun(val text: String, val style: String)

    /** Cortex style → CSS-ish weight name, for renderers that want a simple bool/enum. */
    fun isBold(style: String): Boolean = style == "bold" || style == "bold_italic"
    fun isItalic(style: String): Boolean = style == "italic" || style == "bold_italic"
    fun isCode(style: String): Boolean = style == "code"
    fun isDim(style: String): Boolean = style == "dim"

    /** Parse a runs JSONArray from a Cortex frame into a typed list. Robust to missing fields. */
    fun parseRuns(arr: JSONArray?): List<StyledRun> {
        if (arr == null) return emptyList()
        val out = mutableListOf<StyledRun>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("text", "")
            val s = o.optString("style", "normal")
            if (t.isNotEmpty()) out.add(StyledRun(t, s))
        }
        return out
    }

    /**
     * Flatten runs into a single (text, dominantStyle) pair. Used when the
     * renderer can't show per-run styles (e.g. headless log surface) and just
     * wants the overall flavor.
     *
     * Dominance order: bold > italic > normal. Code / dim don't outrank normal.
     */
    fun flatten(runs: List<StyledRun>): Pair<String, String> {
        var dominant = "normal"
        val sb = StringBuilder()
        for ((t, s) in runs) {
            sb.append(t)
            if (isBold(s)) dominant = "bold"
            else if (isItalic(s) && dominant == "normal") dominant = "italic"
        }
        return sb.toString() to dominant
    }

    /** Sugar: parse + flatten in one go. */
    fun flattenJson(arr: JSONArray?): Pair<String, String> = flatten(parseRuns(arr))
}
