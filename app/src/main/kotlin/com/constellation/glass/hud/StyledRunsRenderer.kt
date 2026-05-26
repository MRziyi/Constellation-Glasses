package com.constellation.glass.hud

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts a Cortex `[{"text":..., "style":"bold"|"italic"|...}]` styled-runs
 * array into one or more [SelfViewJson] TextView nodes for the HUD layout.
 *
 * We render each run as a SEPARATE TextView with the right `textStyle`. The
 * SDK's TextView doesn't support per-character spans, so multiple TextViews
 * arranged horizontally is the simplest path. For body content the runs are
 * concatenated INSIDE a single TextView with the dominant style (no per-run
 * styling for body) — Glass HUD only really needs bold-vs-normal for body
 * emphasis; finer-grained inline mixing is overkill for the use case.
 *
 * Two API surfaces:
 *   - `runsToTextViews(...)` — fan out one TextView per run (used for short
 *     headlines like card titles)
 *   - `runsToFlatText(...)` — concatenate with markers so a single TextView
 *     gets the dominant style (used for body where line wrap matters)
 */
object StyledRunsRenderer {

    /** Map a Cortex style name to CXR-L TextView textStyle. */
    fun ttStyle(s: String): String? = when (s) {
        "bold"        -> "bold"
        "italic"      -> "italic"
        "bold_italic" -> "bold_italic"
        else          -> null  // normal / code / dim → null (default style)
    }

    /** Brightness tier in green channel per Cortex style → JBD4020 mapping. */
    fun colorFor(s: String, defaultMid: String = "#00CC00"): String = when (s) {
        "bold", "bold_italic" -> "#00FF00"        // bright
        "dim"                 -> "#008800"        // dim
        "code"                -> "#7AB37A"        // dim-tinted (lower contrast)
        else                  -> defaultMid       // normal / italic
    }

    /**
     * Parse a runs JSONArray from a Cortex frame into a list of (text, style).
     * Robust to missing fields.
     */
    fun parseRuns(arr: JSONArray?): List<Pair<String, String>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("text", "")
            val s = o.optString("style", "normal")
            if (t.isNotEmpty()) out.add(t to s)
        }
        return out
    }

    /** Concatenate run texts; return the dominant style across all runs.
     *  Used when we want one TextView to render a whole line. Bold > italic > normal. */
    fun flatten(runs: List<Pair<String, String>>): Pair<String, String> {
        var dominant = "normal"
        val sb = StringBuilder()
        for ((t, s) in runs) {
            sb.append(t)
            if (s == "bold" || s == "bold_italic") dominant = "bold"
            else if (s == "italic" && dominant == "normal") dominant = "italic"
        }
        return sb.toString() to dominant
    }

    /** Build a TextView SelfViewJson for a list of styled runs (flattened). */
    fun buildTextView(
        id: String,
        runs: List<Pair<String, String>>,
        textSize: String,
        defaultColor: String = "#00CC00",
        gravity: String? = null,
    ): SelfViewJson {
        val (text, dominantStyle) = flatten(runs)
        val props = TextViewProps().apply {
            this.id = id
            this.layout_width = "match_parent"
            this.layout_height = "wrap_content"
            this.text = text
            this.textColor = colorFor(dominantStyle, defaultColor)
            this.textSize = textSize
            ttStyle(dominantStyle)?.let { this.textStyle = it }
            gravity?.let { this.gravity = it }
        }
        return SelfViewJson().apply {
            type = "TextView"
            this.props = props.toJson()
        }
    }
}
