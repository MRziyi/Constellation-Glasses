package com.constellation.glass.state

import com.constellation.glass.audio.AudioPipeline
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
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
    /** Optional — when null, mic_open/mic_close are logged but no audio is
     *  captured. Useful in tests / non-mic builds. */
    private val audio: AudioPipeline? = null,
) {

    /** Latest partial transcript run from the server (Level 2 streaming).
     *  Cleared on each new Listening entry. */
    private var partialRuns: org.json.JSONArray? = null

    /** Start collecting inbound + connection-state. Called once from
     *  ConstellationService.onStartCommand. */
    @OptIn(FlowPreview::class)
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
        // Level 1: drive the g-wave from local PCM amplitude. Sample at ~10 Hz
        // so we don't flood the CustomView update channel with 60+ Hz ticks.
        audio?.let { ap ->
            scope.launch {
                ap.amplitude.sample(100).collect { amp ->
                    if (stateFlow.value == AppState.Listening) {
                        hudRenderer.updateListening(
                            elapsedSec = 0,
                            amplitude = amp,
                            partialRuns = partialRuns,
                        )
                    }
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
        val stage = frame["stage"]?.jsonPrimitive?.contentOrNull ?: ""
        val detailRuns = frame["detail_runs"]?.let { jsonToOrg(it) }
        val metaRuns = frame["meta_runs"]?.let { jsonToOrg(it) }
        val icon = frame["icon"]?.jsonPrimitive?.contentOrNull ?: ""

        // Level 2: server-emitted partial transcripts arrive as
        // hud_state(stage="listening", detail_runs=[<partial>]). Keep the
        // mic affordance up; just refresh the partial-text region.
        if (stage == "listening" || stateFlow.value == AppState.Listening) {
            if (stateFlow.value != AppState.Listening) transitionTo(AppState.Listening)
            partialRuns = detailRuns
            hudRenderer.updateListening(
                elapsedSec = 0,
                amplitude = 0f,
                partialRuns = detailRuns,
            )
            return
        }

        if (stateFlow.value !in setOf(AppState.Thinking, AppState.Listening)) {
            transitionTo(AppState.Thinking)
        }
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
        val streamId = frame["stream_id"]?.jsonPrimitive?.contentOrNull
        val langHint = frame["lang_hint"]?.jsonPrimitive?.contentOrNull
        Timber.i("StateMachine · mic_open stream_id=$streamId lang_hint=$langHint")
        if (streamId == null) return
        // Enter Listening (g-wave + partial transcript area) and kick off
        // capture. If we were in CARD (the modify flow), the transition is
        // a deliberate hand-off — the card state is gone, but the cmd_id
        // semantic is already baked into the stream_id ("modify_<cmd_id>")
        // so Cortex routes the eventual audio_end correctly.
        partialRuns = null
        if (stateFlow.value != AppState.Listening) transitionTo(AppState.Listening)
        audio?.start(streamId, langHint)
    }

    private fun handleMicClose(frame: JsonObject) {
        val streamId = frame["stream_id"]?.jsonPrimitive?.contentOrNull
        Timber.i("StateMachine · mic_close stream_id=$streamId")
        audio?.stop()
        // Don't auto-transition here — Cortex will follow up with hud_state
        // (transcribing…) or a card. If neither arrives we'll eventually
        // fall back to Idle on the next progress beat.
    }

    // ── state transition (HUD lifecycle) ────────────────────────────────

    private fun transitionTo(next: AppState) {
        val prev = stateFlow.value
        if (prev == next) return
        Timber.i("StateMachine · $prev → $next")
        stateFlow.value = next
        // Leaving Listening for anything except Listening itself means the
        // mic must stop (Cortex's mic_close may already have fired, but we
        // also stop here as a safety net for state-changes that bypass it).
        if (prev == AppState.Listening && next != AppState.Listening) {
            audio?.stop()
            partialRuns = null
        }
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
