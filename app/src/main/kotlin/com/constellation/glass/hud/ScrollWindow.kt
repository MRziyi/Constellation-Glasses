package com.constellation.glass.hud

/**
 * Viewport into a long card body. The Glass renders 6 lines at a time;
 * the wearer scrolls via voice ("下一段" / "上一段") or ring SWIPE_UP/DOWN.
 *
 * Holds a list of pre-wrapped lines (already broken at the right column).
 * `windowText()` returns the visible slice; `scrollDown()` / `scrollUp()`
 * shift by one line and clamp to the bounds.
 */
class ScrollWindow(
    private val lines: List<String>,
    /** How many lines fit in the body region (HUD safe-zone). */
    val windowSize: Int = 6,
) {
    private var topIndex: Int = 0

    fun windowText(): String =
        lines.subList(topIndex, (topIndex + windowSize).coerceAtMost(lines.size))
            .joinToString("\n")

    /** Returns true if the window actually moved. */
    fun scrollDown(): Boolean {
        val maxTop = (lines.size - windowSize).coerceAtLeast(0)
        if (topIndex >= maxTop) return false
        topIndex += 1
        return true
    }

    fun scrollUp(): Boolean {
        if (topIndex == 0) return false
        topIndex -= 1
        return true
    }

    fun atTop(): Boolean = topIndex == 0
    fun atBottom(): Boolean = topIndex >= (lines.size - windowSize).coerceAtLeast(0)
    fun position(): Int = topIndex + 1                  // 1-based
    fun totalWindows(): Int = ((lines.size + windowSize - 1) / windowSize).coerceAtLeast(1)
    fun linesRemaining(): Int = (lines.size - topIndex - windowSize).coerceAtLeast(0)

    companion object {
        /**
         * Hard-wrap text into lines of `maxChars` chars, breaking on word
         * boundaries when possible. Preserves explicit "\n" in the source.
         */
        fun wrap(text: String, maxChars: Int = 32): List<String> {
            val out = mutableListOf<String>()
            for (paragraph in text.split('\n')) {
                if (paragraph.isEmpty()) {
                    out.add("")
                    continue
                }
                var s = paragraph
                while (s.length > maxChars) {
                    // Try to break at the last space within the window
                    val breakAt = s.lastIndexOf(' ', maxChars).takeIf { it > maxChars / 2 }
                        ?: maxChars
                    out.add(s.substring(0, breakAt).trim())
                    s = s.substring(breakAt).trim()
                }
                if (s.isNotEmpty()) out.add(s)
            }
            return out
        }
    }
}
