package com.constellation.glass.state

import com.constellation.glass.hud.HudSurface
import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import timber.log.Timber

/**
 * Routes inbound Cortex commands + outbound user events through the HUD
 * state lifecycle.
 *
 * Lifecycle invariants:
 *  - CustomView open in Listening / Thinking / Card / Insight / Offline; closed in Idle
 *  - Mic open only in Listening + Card-auto-open phase  (wired in Phase 3b.4 Glass-side)
 *  - Halo Ring profile push on each transition           (wired in Phase 3b.3)
 *  - InstructSdk vocabulary registered per state          (wired in Phase 3b.3)
 */
class StateMachine(
    private val scope: CoroutineScope,
    private val wss: WssClient,
    private val stateFlow: MutableStateFlow<AppState>,
    private val hudRenderer: HudSurface,
) {

    /** Start collecting inbound + connection-state. Called once from
     *  ConstellationService.onStartCommand. */
    fun bind() {
        scope.launch {
            wss.inbound.collect { frame -> dispatch(frame) }
        }
        scope.launch {
            wss.connected.collect { isConnected ->
                if (!isConnected && stateFlow.value != AppState.Offline) {
                    Timber.i("StateMachine · WSS down → Offline")
                    transitionTo(AppState.Offline)
                } else if (isConnected && stateFlow.value == AppState.Offline) {
                    Timber.i("StateMachine · WSS up → Idle")
                    transitionTo(AppState.Idle)
                }
            }
        }
    }

    private fun dispatch(frame: JsonObject) {
        val kind = frame["kind"]?.jsonPrimitive?.contentOrNull ?: return
        when (kind) {
            "hud_state" -> handleHudState(frame)
            "card"      -> handleCard(frame)
            "insight"   -> handleInsight(frame)
            "mic_open"  -> handleMicOpen(frame)
            "mic_close" -> handleMicClose(frame)
            "progress"  -> {
                // Legacy frame from the existing Console-shaped stream.
                // We piggyback on it for hud_state too (when the peer also
                // sends hud_state, we'll get both — preferring hud_state).
                Timber.v("StateMachine · progress (legacy frame, ignored — hud_state expected)")
            }
            else -> Timber.v("StateMachine · unhandled kind=$kind")
        }
    }

    // ── per-kind handlers ───────────────────────────────────────────────

    private fun handleHudState(frame: JsonObject) {
        // Transition into Thinking if we weren't already there. (Listening
        // also receives hud_state for "transcribing…"; we stay in Listening
        // to keep the mic affordance correct.)
        if (stateFlow.value !in setOf(AppState.Thinking, AppState.Listening)) {
            transitionTo(AppState.Thinking)
        }
        val icon = frame["icon"]?.jsonPrimitive?.contentOrNull ?: ""
        val detailRuns = frame["detail_runs"]?.let { jsonToOrg(it) }
        val metaRuns = frame["meta_runs"]?.let { jsonToOrg(it) }
        hudRenderer.updateThinking(icon, detailRuns, metaRuns)
    }

    private fun handleCard(frame: JsonObject) {
        transitionTo(AppState.Card)
        val cardId = frame["cmd_id"]?.jsonPrimitive?.contentOrNull ?: "?"
        val titleRuns = frame["title_runs"]?.let { jsonToOrg(it) }
        val bodyRuns = frame["body_runs"]?.let { jsonToOrg(it) }
        val options = frame["options"]?.let { el ->
            try {
                el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            } catch (_: Throwable) { emptyList() }
        } ?: listOf("approve", "modify", "kill")
        hudRenderer.showCard(cardId, titleRuns, bodyRuns, options)
    }

    private fun handleInsight(frame: JsonObject) {
        // Only accept in Idle (per non-neg #9 / design §1.5).
        if (stateFlow.value != AppState.Idle) {
            Timber.i("StateMachine · insight dropped (state=${stateFlow.value})")
            return
        }
        transitionTo(AppState.Insight)
        val titleRuns = frame["title_runs"]?.let { jsonToOrg(it) }
        val bodyRuns = frame["body_runs"]?.let { jsonToOrg(it) }
        val ttlMs = frame["ttl_ms"]?.jsonPrimitive?.intOrNull ?: 8_000
        hudRenderer.showInsight(titleRuns, bodyRuns, ttlSec = ttlMs / 1000)
        // TODO Phase 3b.4: auto-close after ttlMs (or "engage" voice command)
    }

    private fun handleMicOpen(frame: JsonObject) {
        // TODO Phase 3b.4: AudioPipeline.startCapture(streamId, langHint)
        Timber.i("StateMachine · mic_open stream_id=${frame["stream_id"]}")
    }

    private fun handleMicClose(frame: JsonObject) {
        // TODO Phase 3b.4: AudioPipeline.stopCapture(streamId)
        Timber.i("StateMachine · mic_close stream_id=${frame["stream_id"]}")
    }

    // ── state transition (HUD lifecycle) ────────────────────────────────

    private fun transitionTo(next: AppState) {
        val prev = stateFlow.value
        if (prev == next) return
        Timber.i("StateMachine · $prev → $next")
        stateFlow.value = next
        hudRenderer.transition(prev, next)
        // TODO Phase 3b.3: InstructHost.activateForState(next)
        // TODO Phase 3b.3: HaloProfilePush.pushFor(next)  (if ring paired)
    }

    // ── helper: kotlinx.serialization.json.JsonElement → org.json.JSONArray ──
    // HudRenderer takes org.json types for runs (it's lighter inside the
    // hud module). We convert at the boundary.
    private fun jsonToOrg(el: JsonElement): JSONArray? {
        return try {
            JSONArray(el.toString())
        } catch (t: Throwable) {
            Timber.w(t, "StateMachine · failed to convert runs JsonElement to JSONArray")
            null
        }
    }
}
