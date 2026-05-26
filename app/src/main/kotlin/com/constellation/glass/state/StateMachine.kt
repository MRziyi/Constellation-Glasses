package com.constellation.glass.state

import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Routes inbound Cortex commands + outbound user events through the HUD
 * state lifecycle. Phase 3b.1 wires the skeleton — actual HUD render +
 * mic capture + InstructSdk dispatch land in 3b.2 / 3b.3 / 3b.4.
 *
 * Lifecycle invariants:
 *  - CustomView open in Listening/Thinking/Card/Insight/Offline; closed in Idle
 *  - Mic open only in Listening + Card-auto-open phase
 *  - Halo Ring profile push on each transition (no-op if ring isn't paired)
 */
class StateMachine(
    private val scope: CoroutineScope,
    private val wss: WssClient,
    private val stateFlow: MutableStateFlow<AppState>,
) {

    /** Start collecting inbound + connection-state events. Called once from
     *  ConstellationService.onStartCommand. */
    fun bind() {
        // Inbound Cortex frames → state transitions
        scope.launch {
            wss.inbound.collect { frame -> dispatch(frame) }
        }
        // Connection drops → OFFLINE overlay (and back when it returns)
        scope.launch {
            wss.connected.collect { isConnected ->
                if (!isConnected && stateFlow.value != AppState.Offline) {
                    Timber.i("StateMachine · WSS down → Offline")
                    transitionTo(AppState.Offline)
                } else if (isConnected && stateFlow.value == AppState.Offline) {
                    Timber.i("StateMachine · WSS up → back to Idle")
                    transitionTo(AppState.Idle)
                }
            }
        }
    }

    private suspend fun dispatch(frame: JsonObject) {
        val kind = frame["kind"]?.jsonPrimitive?.content ?: return
        when (kind) {
            "hud_state" -> {
                // Live thinking row — open CustomView if needed; otherwise
                // updateCustomView with the new text setters.
                if (stateFlow.value !in setOf(AppState.Thinking, AppState.Listening)) {
                    transitionTo(AppState.Thinking)
                }
                // TODO Phase 3b.2: HudRenderer.update(detailRuns, metaRuns)
                Timber.v("StateMachine · hud_state forwarded (Thinking)")
            }
            "card" -> {
                transitionTo(AppState.Card)
                // TODO Phase 3b.2: HudRenderer.showCard(titleRuns, bodyRuns, options)
                // TODO Phase 3b.4: mic_open auto-fired client-side at CARD entry
                Timber.v("StateMachine · card surfaced")
            }
            "insight" -> {
                // Only accept from Idle (per non-neg #9)
                if (stateFlow.value == AppState.Idle) {
                    transitionTo(AppState.Insight)
                    // TODO Phase 3b.2: HudRenderer.showInsight(...)
                } else {
                    Timber.i("StateMachine · insight dropped (not Idle)")
                }
            }
            "mic_open" -> {
                // Server explicitly opens mic — usually at CARD entry
                // TODO Phase 3b.4: AudioPipeline.startCapture(streamId)
                Timber.v("StateMachine · mic_open")
            }
            "mic_close" -> {
                // TODO Phase 3b.4: AudioPipeline.stopCapture()
                Timber.v("StateMachine · mic_close")
            }
            "progress" -> {
                // Pre-Phase-3b.2: log only. Once HUD wired, treat as Thinking.
                Timber.v("StateMachine · progress (legacy frame)")
            }
            else -> Timber.v("StateMachine · unhandled kind=$kind")
        }
    }

    private fun transitionTo(next: AppState) {
        val prev = stateFlow.value
        if (prev == next) return
        Timber.i("StateMachine · $prev → $next")
        stateFlow.value = next
        // TODO Phase 3b.2: HudRenderer.transition(prev, next)
        // TODO Phase 3b.3: InstructHost.activateForState(next)
        // TODO Phase 3b.3: HaloProfilePush.pushFor(next)  (if ring paired)
    }
}
