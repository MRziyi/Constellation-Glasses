package com.constellation.glass

import android.content.Context
import com.constellation.glass.halo.HaloOverlay
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * User-rebindable ring-gesture → card-decision map (Zack 2026-06-02).
 *
 * A HUD decision card has exactly three actions; each is bound to ONE ring
 * gesture. Defaults retire the bare TAP/DOUBLE_TAP (too easy to mis-trigger —
 * a stray tap once sent an email) in favour of a deliberate tap-then-swipe for
 * the two terminal actions, and long-press for modify:
 *
 *   APPROVE → TAP_SWIPE_UP   (单击+上)   send / approve / engage
 *   KILL    → TAP_SWIPE_DOWN (单击+下)   reject / kill / dismiss
 *   MODIFY  → LONG_PRESS     (长按)      revise & re-confirm
 *
 * The wearer can rebind any action in Settings → Gestures. Bare SWIPE_UP /
 * SWIPE_DOWN stay scroll UNLESS a decision is explicitly bound onto them.
 *
 * Stored as JSON in filesDir (synchronous, like [ShortcutSlots]) with an
 * in-memory [cache] so the gesture hot-path ([ConstellationService.hudGesture])
 * reads it without a coroutine. UI strings English-only ([[ui-strings-english-only]]).
 */
object GestureBindings {

    enum class Action { APPROVE, KILL, MODIFY }

    /** Gestures the wearer may pick from when rebinding (cycled in the UI). */
    val SELECTABLE: List<String> = listOf(
        HaloOverlay.G_TAP_SWIPE_UP,
        HaloOverlay.G_TAP_SWIPE_DOWN,
        HaloOverlay.G_LONG_PRESS,
        HaloOverlay.G_DOUBLE_TAP,
        HaloOverlay.G_TAP,
        HaloOverlay.G_SWIPE_UP,
        HaloOverlay.G_SWIPE_DOWN,
    )

    private val DEFAULTS: Map<Action, String> = mapOf(
        Action.APPROVE to HaloOverlay.G_TAP_SWIPE_UP,
        Action.KILL to HaloOverlay.G_TAP_SWIPE_DOWN,
        Action.MODIFY to HaloOverlay.G_LONG_PRESS,
    )

    private const val FILE = "gesture_bindings.json"

    @Volatile private var cache: Map<Action, String> = DEFAULTS

    /** Short English label for a gesture (HUD footer hints + settings rows). */
    fun label(gesture: String): String = when (gesture) {
        HaloOverlay.G_TAP_SWIPE_UP -> "tap+up"
        HaloOverlay.G_TAP_SWIPE_DOWN -> "tap+down"
        HaloOverlay.G_LONG_PRESS -> "long-press"
        HaloOverlay.G_DOUBLE_TAP -> "double-tap"
        HaloOverlay.G_TAP -> "tap"
        HaloOverlay.G_SWIPE_UP -> "swipe up"
        HaloOverlay.G_SWIPE_DOWN -> "swipe down"
        else -> gesture
    }

    /** The gesture currently bound to [action]. */
    fun gestureFor(action: Action): String = cache[action] ?: DEFAULTS.getValue(action)

    /** Footer label for [action]'s bound gesture (e.g. "tap+up"). */
    fun labelFor(action: Action): String = label(gestureFor(action))

    /** Reverse lookup: the decision [Action] this raw gesture triggers, or null
     *  (so the caller can fall through to scroll / ignore). */
    fun actionFor(gesture: String): Action? =
        cache.entries.firstOrNull { it.value == gesture }?.key

    /** Snapshot of all bindings (for the settings screen). */
    fun all(): Map<Action, String> = cache

    /** Load persisted overrides into [cache]. Idempotent; call at startup. */
    fun load(ctx: Context) {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) { cache = DEFAULTS; return }
        cache = try {
            val o = JSONObject(f.readText())
            Action.values().associateWith { a ->
                o.optString(a.name, DEFAULTS.getValue(a)).ifBlank { DEFAULTS.getValue(a) }
            }
        } catch (t: Throwable) {
            Timber.w(t, "GestureBindings · read failed; using defaults")
            DEFAULTS
        }
    }

    /** Rebind [action] → [gesture]; persists + refreshes the cache immediately. */
    fun set(ctx: Context, action: Action, gesture: String) {
        cache = cache.toMutableMap().apply { put(action, gesture) }
        try {
            val o = JSONObject()
            cache.forEach { (a, g) -> o.put(a.name, g) }
            File(ctx.filesDir, FILE).writeText(o.toString())
            Timber.i("GestureBindings · $action → $gesture")
        } catch (t: Throwable) {
            Timber.w(t, "GestureBindings · write failed")
        }
    }
}
