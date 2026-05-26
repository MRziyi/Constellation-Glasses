package com.constellation.glass.input

/**
 * Routes platform-specific input gestures into the state machine. Implemented
 * by `ConstellationService` (the only thing that needs to react to input).
 *
 * Platform-specific source sets translate hardware events into these calls:
 *  - `glass` flavor → SystemKeyReceiver subscribes to
 *    `com.android.action.ACTION_SPRITE_BUTTON_*` + `..._TWO_FINGER_*`
 *    broadcasts on R08 and routes them here. See
 *    [reference/rokid-glass/bare-metal-docs/01-key-events.md].
 *  - `phoneDebug` flavor → DebugKeyReceiver posts a persistent notification
 *    with one action button per gesture so we can drive the same flow on a
 *    regular phone without specialized hardware.
 *
 * Semantic mapping (glass):
 *  - **Primary click** (single press of right-temple button) — the main
 *    interaction: IDLE→Listening, LISTENING→THINKING, CARD→Approve
 *  - **Primary long-press** — IDLE→Wake / CARD→Modify
 *  - **Primary double-click** — system-occupied as "back" on R08; not
 *    interceptable. We map it semantically to Kill / dismiss-to-IDLE.
 *  - **Two-finger swipes** — CARD scroll
 *  - **Two-finger tap / double-tap** — CARD modify / kill alt
 *  - **Settings key** (two-finger long-press on R08) — opens Settings (future)
 */
interface InputHandler {
    fun onPrimaryClick()
    fun onPrimaryLongPress()
    fun onPrimaryDoubleClick()
    fun onTwoFingerSingleTap()
    fun onTwoFingerDoubleTap()
    fun onTwoFingerSwipeForward()
    fun onTwoFingerSwipeBack()
    fun onSettingsKey()
}
