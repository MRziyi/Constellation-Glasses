package com.constellation.glass.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.constellation.glass.input.InputHandler
import timber.log.Timber

/**
 * Subscribes to R08's system-broadcast key events and routes them into our
 * [InputHandler]. Glass-flavor only — phoneDebug uses notification action
 * buttons instead.
 *
 * Source: `reference/rokid-glass/bare-metal-docs/01-key-events.md`.
 *
 * The receiver is registered programmatically (not via manifest) because
 * manifest-declared ordered-broadcast receivers can't call
 * `abortBroadcast()` — and we want to suppress the system's default
 * handling of CLICK / LONG_PRESS / TWO_FINGER_* so they don't also dismiss
 * our HUD or trigger Sprite UI.
 *
 * The only thing we **cannot** intercept is `DOUBLE_CLICK` — the system
 * uses it as a hardware back/exit and forces dispatch regardless of
 * priority. We handle it but expect our Activity to be paused as a
 * side-effect.
 */
class SystemKeyReceiver(private val handler: InputHandler) : BroadcastReceiver() {

    companion object {
        const val A_CLICK    = "com.android.action.ACTION_SPRITE_BUTTON_CLICK"
        const val A_DOWN     = "com.android.action.ACTION_SPRITE_BUTTON_DOWN"
        const val A_UP       = "com.android.action.ACTION_SPRITE_BUTTON_UP"
        const val A_DBL      = "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK"
        const val A_LONG     = "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS"
        // AI_START = touchpad long-press; system-occupied → Sprite. Don't intercept.
        const val A_AI_START = "com.android.action.ACTION_AI_START"
        const val A_TF_TAP   = "com.android.action.ACTION_TWO_FINGER_SINGLE_TAP"
        const val A_TF_DBL   = "com.android.action.ACTION_TWO_FINGER_DOUBLE_TAP"
        const val A_TF_FWD   = "com.android.action.ACTION_TWO_FINGER_SWIPE_FORWARD"
        const val A_TF_BACK  = "com.android.action.ACTION_TWO_FINGER_SWIPE_BACK"
        const val A_SETTINGS = "com.android.action.ACTION_SETTINGS_KEY"

        fun intentFilter(): IntentFilter = IntentFilter().apply {
            priority = 100  // intercept ordered broadcasts before system defaults
            addAction(A_CLICK); addAction(A_LONG); addAction(A_DBL)
            addAction(A_DOWN); addAction(A_UP)
            addAction(A_TF_TAP); addAction(A_TF_DBL)
            addAction(A_TF_FWD); addAction(A_TF_BACK)
            addAction(A_SETTINGS)
            // We do NOT add A_AI_START — that's Sprite's touchpad long-press
            // path, which we explicitly let through (user might still want
            // Hi-Rokid even though we don't depend on it).
        }
    }

    override fun onReceive(ctx: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Timber.v("SystemKeyReceiver · $action")
        when (action) {
            A_CLICK    -> { handler.onPrimaryClick();           abortBroadcast() }
            A_LONG     -> { handler.onPrimaryLongPress();       abortBroadcast() }
            A_DBL      -> { handler.onPrimaryDoubleClick() /* not abortable per system contract */ }
            A_TF_TAP   -> { handler.onTwoFingerSingleTap();     abortBroadcast() }
            A_TF_DBL   -> { handler.onTwoFingerDoubleTap();     abortBroadcast() }
            A_TF_FWD   -> { handler.onTwoFingerSwipeForward();  abortBroadcast() }
            A_TF_BACK  -> { handler.onTwoFingerSwipeBack();     abortBroadcast() }
            A_SETTINGS -> { handler.onSettingsKey();            abortBroadcast() }
            A_DOWN, A_UP -> {
                // We don't expose down/up to InputHandler currently — single
                // CLICK is what state machine cares about.
            }
        }
    }
}
