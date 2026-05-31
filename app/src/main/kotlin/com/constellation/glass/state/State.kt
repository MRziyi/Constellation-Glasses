package com.constellation.glass.state

/**
 * The six HUD states (see GLASS-CLIENT-DESIGN.md §5 / ui-mockup.html §1).
 * State transitions own HUD lifecycle (open/update/close), mic lifecycle,
 * and InstructSdk vocabulary registration.
 */
enum class AppState {
    /** HUD closed. Mic off. Only InstructSdk wake words active. */
    Idle,

    /** HUD: "🎤 listening…". Mic open, streaming PCM via audio_chunk. */
    Listening,

    /** HUD: live single-row status, replaced in place at <=4 Hz. */
    Thinking,

    /** HUD: title + scrollable body. Mic auto-opens 30 s. */
    Card,

    /** Transient sub-state from Idle: proactive HUD push, 8 s TTL. */
    Insight,

    /** Overlay error: WSS down >5s. Auto-exits on reconnect. Double-tap dismisses. */
    Offline,

    /** Overlay: auth expired (Edge 401/403 — stale cookie, reconnect halted).
     *  LONG = open the camera scanner to re-pair; DOUBLE = dismiss. Distinct
     *  from Offline (which auto-retries); this one needs the wearer to re-pair. */
    AuthExpired,
}
