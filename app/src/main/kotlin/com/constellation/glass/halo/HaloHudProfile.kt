package com.constellation.glass.halo

import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Pushes / pops the `constellation_hud` gesture profile on the Halo Ring while
 * the HUD overlay is up (Doc/18 §5 — Profile push/pop).
 *
 * While the profile is on the ring's LIFO [ProfileStack], every bound gesture
 * routes a TRIGGER back to [HaloTriggerReceiver] instead of doing the wearer's
 * normal profile action — i.e. the HUD exclusively owns the ring while visible.
 * System gestures (TRIPLE_TAP / QUADRUPLE_TAP / LP+SWIPE / DOUBLE_LONG_PRESS)
 * always bypass the stack by design, so the wearer can never be locked out.
 *
 * Gesture → action mapping (1:1 onto the StateMachine's state-aware handlers
 * via [HaloTriggerReceiver]):
 *   TAP        → hud_activate   (Card: Approve · Listening: end utterance · Insight: engage)
 *   DOUBLE_TAP → hud_dismiss    (Card: Kill / info-only dismiss)
 *   LONG_PRESS → hud_modify     (Card: Modify — opens mic for re-plan voice)
 *   SWIPE_UP   → hud_scroll_up
 *   SWIPE_DOWN → hud_scroll_down
 *
 * Target package is the ring's installed Rokid flavor (`com.halo.ring` base
 * applicationId + `.rokid` suffix). PROFILE_PUSH is gated by the
 * signature|privileged `PUSH_PROFILE` permission, granted because Constellation
 * and Halo Ring share a signing cert. All broadcasts are fire-and-forget: a
 * no-op if the ring isn't installed / running.
 */
object HaloHudProfile {

    private const val RING_PKG = "com.halo.ring.rokid"
    private const val ACTION_PUSH = "com.halo.ring.action.PROFILE_PUSH"
    private const val ACTION_POP = "com.halo.ring.action.PROFILE_POP"
    private const val PROFILE_ID = "constellation_hud"

    const val ACT_ACTIVATE = "hud_activate"
    const val ACT_DISMISS = "hud_dismiss"
    const val ACT_MODIFY = "hud_modify"
    const val ACT_SCROLL_UP = "hud_scroll_up"
    const val ACT_SCROLL_DOWN = "hud_scroll_down"

    private fun bindingsJson(ownerPkg: String): String {
        fun ext(actionId: String) =
            """{"type":"external","package":"$ownerPkg","action_id":"$actionId"}"""
        return """
            {
              "TAP":        ${ext(ACT_ACTIVATE)},
              "DOUBLE_TAP": ${ext(ACT_DISMISS)},
              "LONG_PRESS": ${ext(ACT_MODIFY)},
              "SWIPE_UP":   ${ext(ACT_SCROLL_UP)},
              "SWIPE_DOWN": ${ext(ACT_SCROLL_DOWN)}
            }
        """.trimIndent()
    }

    /** Push the HUD profile so the ring exclusively drives card actions. Safe to
     *  call repeatedly — the ring's ProfileStack replaces a re-pushed
     *  (owner, profileId) in place rather than stacking duplicates. */
    fun push(ctx: Context) {
        val owner = ctx.packageName
        val intent = Intent(ACTION_PUSH).apply {
            setPackage(RING_PKG)
            putExtra("profile_id", PROFILE_ID)
            putExtra("owner_package", owner)
            putExtra("bindings_json", bindingsJson(owner))
        }
        try {
            ctx.sendBroadcast(intent)
            Timber.i("HaloHudProfile · PROFILE_PUSH $owner/$PROFILE_ID → $RING_PKG")
        } catch (t: Throwable) {
            Timber.w(t, "HaloHudProfile · PROFILE_PUSH failed")
        }
    }

    /** Pop the HUD profile when the overlay closes, returning the ring to the
     *  wearer's normal profile bindings. */
    fun pop(ctx: Context) {
        val owner = ctx.packageName
        val intent = Intent(ACTION_POP).apply {
            setPackage(RING_PKG)
            putExtra("profile_id", PROFILE_ID)
            putExtra("owner_package", owner)
        }
        try {
            ctx.sendBroadcast(intent)
            Timber.i("HaloHudProfile · PROFILE_POP $owner/$PROFILE_ID → $RING_PKG")
        } catch (t: Throwable) {
            Timber.w(t, "HaloHudProfile · PROFILE_POP failed")
        }
    }
}
