package com.constellation.glass

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * The three fixed shortcut slots (`shortcut1` / `shortcut2` / `shortcut3`).
 *
 * Per Zack's model: the ring exposes exactly two wake surfaces — `voice_invoke`
 * (start/continue a voice agent session) and the shortcut slots (fire a preset
 * prompt without speaking). The slot CONTENT is owned by this app (not
 * hardcoded, not server-enumerated): a prompt, whether to attach a photo
 * upfront, and a display label. Slots are edited via the voice agent — the
 * wearer says "set shortcut 2 to send a photo and ask what's in front", Cortex
 * parses it and sends back a `shortcut_config` frame which [update]s the slot.
 *
 * `sendPhoto` semantics (differs from the default flow): normally the SERVER
 * pulls a photo on demand (R-13). A slot with `sendPhoto=true` attaches the
 * photo UPFRONT at fire time, so the server sees `image≠None` and skips its
 * pull — no duplicate capture. `sendPhoto=false` sends the prompt as text.
 */
object ShortcutSlots {

    const val COUNT = 3
    private const val FILE = "shortcut_slots.json"

    data class Slot(
        val index: Int,            // 1..3
        val label: String,
        val prompt: String,
        val sendPhoto: Boolean,
        // Capture tier when sendPhoto fires (Zack 2026-05-31): "standard"
        // (1024/q85, fast) | "detail" (2048/q90, legible text). Cortex infers it
        // from the config wording; CameraCapture.Tier maps the name → px.
        val tier: String = "standard",
    ) {
        val actionId: String get() = "shortcut$index"
        val configured: Boolean get() = prompt.isNotBlank()
    }

    /** Seed defaults — useful out of the box; all overwritable by voice. */
    private fun default(index: Int): Slot = when (index) {
        1 -> Slot(1, "What's in front of me?", "What's in front of me?", sendPhoto = true)
        else -> Slot(index, "Shortcut $index", "", sendPhoto = false)
    }

    /** All three slots, in order (1..3). Always returns exactly [COUNT]. */
    fun read(ctx: Context): List<Slot> {
        val f = File(ctx.filesDir, FILE)
        val stored: Map<Int, Slot> = if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val idx = o.optInt("index", -1)
                    if (idx in 1..COUNT) idx to Slot(
                        index = idx,
                        label = o.optString("label", "Shortcut $idx"),
                        prompt = o.optString("prompt", ""),
                        sendPhoto = o.optBoolean("send_photo", false),
                        tier = o.optString("tier", "standard"),
                    ) else null
                }.toMap()
            } catch (t: Throwable) {
                Timber.w(t, "ShortcutSlots · read failed; using defaults")
                emptyMap()
            }
        } else emptyMap()
        return (1..COUNT).map { stored[it] ?: default(it) }
    }

    fun get(ctx: Context, index: Int): Slot? =
        read(ctx).firstOrNull { it.index == index }

    /** Partial update of one slot — null args leave the existing value. Returns
     *  the updated slot, or null if [index] is out of range. */
    fun update(
        ctx: Context, index: Int,
        prompt: String? = null, sendPhoto: Boolean? = null, label: String? = null,
        tier: String? = null,
    ): Slot? {
        if (index !in 1..COUNT) {
            Timber.w("ShortcutSlots · update out-of-range index=$index")
            return null
        }
        val slots = read(ctx).toMutableList()
        val cur = slots[index - 1]
        val next = cur.copy(
            prompt = prompt ?: cur.prompt,
            sendPhoto = sendPhoto ?: cur.sendPhoto,
            label = label ?: cur.label,
            tier = tier ?: cur.tier,
        )
        slots[index - 1] = next
        write(ctx, slots)
        Timber.i("ShortcutSlots · slot $index updated · label='${next.label}' photo=${next.sendPhoto} tier=${next.tier}")
        return next
    }

    private fun write(ctx: Context, slots: List<Slot>) {
        val arr = JSONArray()
        slots.forEach { s ->
            arr.put(JSONObject().apply {
                put("index", s.index)
                put("label", s.label)
                put("prompt", s.prompt)
                put("send_photo", s.sendPhoto)
                put("tier", s.tier)
            })
        }
        try {
            File(ctx.filesDir, FILE).writeText(arr.toString())
        } catch (t: Throwable) {
            Timber.w(t, "ShortcutSlots · write failed")
        }
    }
}
