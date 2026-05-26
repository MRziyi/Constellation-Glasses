package com.constellation.glass.hud

/**
 * `LinearLayout` props for `customViewOpen`. Field set + validation mirrors
 * cxrlsample101.dataBean.selfView.LinearLayoutProps, but only the fields we
 * actually use in HudLayouts.
 *
 * Valid gravity values (per SDK whitelist):
 *   center, center_vertical, center_horizontal, top, bottom, start, end
 *
 * NOT supported by the SDK (we tried; layout rejected):
 *   top_start, top_end, bottom_start, bottom_end  ← combine via layout_gravity
 *
 * Colours auto-downsample to the green channel by the system compositor.
 */
class LinearLayoutProps {
    var id: String = ""
    var layout_width: String = "match_parent"
    var layout_height: String = "match_parent"
    var orientation: String = "vertical"

    /** "vertical" | "horizontal" — see class doc for valid values. */
    var gravity: String? = null

    /** Padding values accept "Ndp" or a bare number (we append "dp"). */
    var paddingTop: String? = null
    var paddingBottom: String? = null
    var paddingStart: String? = null
    var paddingEnd: String? = null

    /** Background — auto-downsamples to green channel. */
    var backgroundColor: String? = null

    fun toJson(): String {
        if (id.isEmpty()) error("LinearLayoutProps.id is empty")
        val sb = StringBuilder("{")
            .append("\"id\":\"").append(id).append('"')
            .append(",\"layout_width\":\"").append(layout_width).append('"')
            .append(",\"layout_height\":\"").append(layout_height).append('"')
            .append(",\"orientation\":\"").append(orientation).append('"')
        gravity?.let       { sb.append(",\"gravity\":\"").append(it).append('"') }
        paddingTop?.let    { sb.append(",\"paddingTop\":\"").append(it).append('"') }
        paddingBottom?.let { sb.append(",\"paddingBottom\":\"").append(it).append('"') }
        paddingStart?.let  { sb.append(",\"paddingLeft\":\"").append(it).append('"') }
        paddingEnd?.let    { sb.append(",\"paddingRight\":\"").append(it).append('"') }
        backgroundColor?.let { sb.append(",\"backgroundColor\":\"").append(it).append('"') }
        return sb.append('}').toString()
    }
}
