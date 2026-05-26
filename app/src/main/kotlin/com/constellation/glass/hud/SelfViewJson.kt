package com.constellation.glass.hud

/**
 * CXR-L `customViewOpen` JSON tree node.
 *
 * Wire shape (per cxrlsample101.dataBean.selfView.SelfViewJson):
 *   {"type":"LinearLayout","props":{...},"children":[...]}
 *
 * `props` is itself a JSON object string produced by the per-type props
 * helper (LinearLayoutProps / TextViewProps / ImageViewProps).
 */
class SelfViewJson {
    var type: String = ""

    /** Pre-serialised JSON object (output of `*Props.toJson()`). */
    var props: String = ""

    /** Recursive children. */
    var children: MutableList<SelfViewJson>? = null

    fun toJson(): String {
        if (type.isEmpty()) error("SelfViewJson.type is empty")
        val sb = StringBuilder("{")
        sb.append("\"type\":\"").append(type).append('"')
        sb.append(",\"props\":").append(props)
        children?.let { kids ->
            if (kids.isNotEmpty()) {
                sb.append(",\"children\":[")
                kids.forEachIndexed { i, c ->
                    if (i > 0) sb.append(',')
                    sb.append(c.toJson())
                }
                sb.append(']')
            }
        }
        return sb.append('}').toString()
    }
}


/**
 * Delta update payload for `customViewUpdate`. Carries only changed
 * `(id, props)` pairs. Far cheaper than rebuilding the full tree.
 *
 * Wire shape:
 *   {"update":[{"id":"status_text","props":{"text":"..."}}, ...]}
 */
class UpdateViewJson {
    val updateList: MutableList<UpdateJson> = mutableListOf()

    class UpdateJson(val id: String) {
        val props: MutableMap<String, String> = mutableMapOf()
    }

    fun toJson(): String {
        val sb = StringBuilder("{\"update\":[")
        updateList.forEachIndexed { i, item ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(item.id).append("\",\"props\":{")
            var first = true
            for ((k, v) in item.props) {
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(k).append("\":\"").append(escapeJson(v)).append('"')
            }
            sb.append("}}")
        }
        return sb.append("]}").toString()
    }
}


/** Shared JSON string escaper. The SDK is strict about valid JSON. */
internal fun escapeJson(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '"'  -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else ->
                if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code))
                else sb.append(c)
        }
    }
    return sb.toString()
}
