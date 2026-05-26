package com.constellation.glass.hud

/**
 * `TextView` props for `customViewOpen` / `customViewUpdate`. Field set +
 * validation mirrors cxrlsample101.dataBean.selfView.TextViewProps.
 *
 * Notes:
 *   textStyle accepts "bold" / "italic" / "bold_italic" — omit for normal.
 *   Colours auto-downsample to green channel.
 *   `text` is escaped at toJson() time.
 */
class TextViewProps {
    var id: String = ""
    var layout_width: String = "match_parent"
    var layout_height: String = "wrap_content"
    var text: String = ""
    var textColor: String? = null         // e.g. "#FFFFFF" → green-downsampled
    var textSize: String? = null          // e.g. "22sp" or "22"
    /** center / center_vertical / center_horizontal / start / end / top / bottom */
    var gravity: String? = null
    /** bold / italic / bold_italic — omit for normal */
    var textStyle: String? = null

    var paddingTop: String? = null
    var paddingBottom: String? = null
    var paddingStart: String? = null
    var paddingEnd: String? = null

    fun toJson(): String {
        if (id.isEmpty()) error("TextViewProps.id is empty")
        val sb = StringBuilder("{")
            .append("\"id\":\"").append(id).append('"')
            .append(",\"layout_width\":\"").append(layout_width).append('"')
            .append(",\"layout_height\":\"").append(layout_height).append('"')
            .append(",\"text\":\"").append(escapeJson(text)).append('"')
        textColor?.let     { sb.append(",\"textColor\":\"").append(it).append('"') }
        textSize?.let      { sb.append(",\"textSize\":\"").append(it).append('"') }
        gravity?.let       { sb.append(",\"gravity\":\"").append(it).append('"') }
        textStyle?.let     { sb.append(",\"textStyle\":\"").append(it).append('"') }
        paddingTop?.let    { sb.append(",\"paddingTop\":\"").append(it).append('"') }
        paddingBottom?.let { sb.append(",\"paddingBottom\":\"").append(it).append('"') }
        paddingStart?.let  { sb.append(",\"paddingStart\":\"").append(it).append('"') }
        paddingEnd?.let    { sb.append(",\"paddingEnd\":\"").append(it).append('"') }
        return sb.append('}').toString()
    }
}
