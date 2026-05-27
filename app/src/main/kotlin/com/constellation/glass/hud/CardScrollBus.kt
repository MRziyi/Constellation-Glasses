package com.constellation.glass.hud

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One-way event bus from the (per-flavor) HudSurface input handlers to the
 * Compose `CardHud` composable's scroll state.
 *
 * Why this exists (2026-05-28 F1 redesign): the `2F SWIPE` input events
 * arrive via SystemKeyReceiver → StateMachine → HudSurface.scrollCardUp/Down.
 * The actual `androidx.compose.foundation.ScrollState` lives inside the
 * `CardHud` Composable and isn't accessible from outside. A small singleton
 * SharedFlow lets the HudSurface emit a scroll command that the Composable
 * collects + applies via `scrollState.animateScrollBy`.
 *
 * Replaces the pre-F1 model where `ScrollWindow` pre-paginated the body
 * into N-line chunks at fixed character width. Now Compose handles natural
 * text wrap, and the bus carries "scroll by N px" commands.
 */
object CardScrollBus {

    enum class ScrollCmd { Down, Up }

    private val _events = MutableSharedFlow<ScrollCmd>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ScrollCmd> = _events

    fun emit(cmd: ScrollCmd) { _events.tryEmit(cmd) }
}
