package com.constellation.glass.state

import com.constellation.glass.audio.AudioPipeline
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.wss.GlassEvent
import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.time.Instant
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
    /**
     * R-13 / C-55: callback for `request_image` frames from Cortex. The Service
     * owns the camera capture (needs a Context for CameraGate); StateMachine
     * just delegates here. If null (e.g. unit tests or non-camera builds), the
     * frame is logged and Cortex falls back to image-less dispatch after 10s
     * timeout.
     */
    private val onImageRequested: ((reqId: String, hint: String?) -> Unit)? = null,
) {

    /** Latest partial transcript run from the server (Level 2 streaming).
     *  Cleared on each new Listening entry. */
    private var partialRuns: org.json.JSONArray? = null

    /** ID of the card currently shown (used for Approve / Modify / Kill routing). */
    private var currentCardId: String? = null

    /** Options of the card currently shown. Empty = "info-only" card (no
     *  Approve/Modify/Kill) → controls map to local dismiss + TTL auto-close
     *  rather than emitDecision to Cortex (F2 + F3, 2026-05-28). */
    private var currentCardOptions: List<String> = emptyList()

    /** mic auto-close watchdog — 15s hard cap from v2.1 energy budget. */
    private var micWatchdogJob: Job? = null
    private val micHardCapMs = 15_000L

    /** Insight TTL countdown — auto-closes the Insight HUD if user doesn't
     *  engage within the server-provided window (default 8s). */
    private var insightTtlJob: Job? = null

    /** Info-only card TTL — dynamically sized per body length so a short
     *  "battery: 80%" closes in ~3s and a long photo description gets ~10s.
     *  Cancelled on any state transition out of Card (F3, 2026-05-28). */
    private var cardTtlJob: Job? = null

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
            "request_image" -> handleRequestImage(frame)
            "progress"  -> {
                // Legacy frame from the existing Console-shaped stream.
                // We piggyback on it for hud_state too (when the peer also
                // sends hud_state, we'll get both — preferring hud_state).
                Timber.v("StateMachine · progress (legacy frame, ignored — hud_state expected)")
            }
            "preview_action", "hud_show", "tool_card" -> {
                // Legacy Command frames that Cortex still sends alongside its
                // glass-shaped translation (see Constellation-Server
                // `_send_command()`): preview_action → glass `card` with
                // options; hud_show → glass `card` (info-only) or `insight`;
                // tool_card → glass `card`. The glass-shaped frame is the
                // authoritative one; the legacy frame is a duplicate. Swallow
                // quietly so the catch-all `else` doesn't log it as unhandled.
                Timber.v("StateMachine · legacy command frame ignored (glass-shaped variant authoritative) · kind=$kind")
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
        currentCardId = cardId
        val titleRuns = frame["title_runs"]?.let { jsonToOrg(it) }
        val bodyRuns = frame["body_runs"]?.let { jsonToOrg(it) }
        val options = frame["options"]?.let { el ->
            try {
                el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            } catch (_: Throwable) { emptyList() }
        } ?: listOf("approve", "modify", "kill")
        currentCardOptions = options
        hudRenderer.showCard(cardId, titleRuns, bodyRuns, options)

        // F3 (2026-05-28): info-only cards (no actionable options) auto-close
        // after a body-length-proportional delay. Reading speed ~200 wpm =
        // ~17 chars/sec → ~60ms/char. We use a min/max ceiling so very short
        // bodies don't blink past + very long ones don't sit forever.
        cardTtlJob?.cancel()
        if (options.isEmpty()) {
            // Get the body text length from the runs (best-effort)
            val bodyLen = bodyRuns?.length() ?.let { n ->
                (0 until n).sumOf { i ->
                    bodyRuns.optJSONObject(i)?.optString("text", "")?.length ?: 0
                }
            } ?: 0
            val ttlMs = (3_000L + 50L * bodyLen).coerceIn(3_000L, 30_000L)
            Timber.i("StateMachine · info-only card TTL = ${ttlMs}ms (body len=$bodyLen)")
            cardTtlJob = scope.launch {
                delay(ttlMs)
                if (stateFlow.value == AppState.Card && currentCardOptions.isEmpty()) {
                    Timber.i("StateMachine · info-only card TTL expired → Idle")
                    dismissCardLocally()
                }
            }
        }
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
        // Auto-close after ttlMs unless user engages with a primary click.
        insightTtlJob?.cancel()
        insightTtlJob = scope.launch {
            delay(ttlMs.toLong())
            if (stateFlow.value == AppState.Insight) {
                Timber.i("StateMachine · insight TTL expired → Idle")
                transitionTo(AppState.Idle)
            }
        }
    }

    private fun handleMicOpen(frame: JsonObject) {
        val streamId = frame["stream_id"]?.jsonPrimitive?.contentOrNull
        val langHint = frame["lang_hint"]?.jsonPrimitive?.contentOrNull
        Timber.i("StateMachine · mic_open stream_id=$streamId lang_hint=$langHint")
        if (streamId == null) return
        startListening(streamId, langHint)
    }

    private fun handleMicClose(frame: JsonObject) {
        val streamId = frame["stream_id"]?.jsonPrimitive?.contentOrNull
        Timber.i("StateMachine · mic_close stream_id=$streamId")
        stopListening()
    }

    private fun startListening(streamId: String, langHint: String? = null) {
        partialRuns = null
        if (stateFlow.value != AppState.Listening) transitionTo(AppState.Listening)
        audio?.start(streamId, langHint)
        // 15s hard cap — energy budget safety net (v2.1 §3.3).
        micWatchdogJob?.cancel()
        micWatchdogJob = scope.launch {
            delay(micHardCapMs)
            if (stateFlow.value == AppState.Listening) {
                Timber.w("StateMachine · mic hard-cap (15s) — auto-stop")
                stopListening()
                transitionTo(AppState.Thinking)
            }
        }
    }

    private fun stopListening() {
        micWatchdogJob?.cancel()
        micWatchdogJob = null
        audio?.stop()
    }

    // ── User input routing (v2.1 — physical keys replace voice wake) ────────

    /** Single click of the right-temple button. Primary interaction.
     *
     *  Card semantics (F2, 2026-05-28):
     *    - actionable card (options non-empty) → emit "Approve" to Cortex
     *    - info-only card (options=[])        → dismiss locally (no Cortex msg)
     */
    fun handlePrimaryClick() {
        when (stateFlow.value) {
            AppState.Idle -> {
                // Wake the system and open mic for a fresh ask.
                val streamId = "fresh_${System.currentTimeMillis()}"
                startListening(streamId)
            }
            AppState.Listening -> {
                // Confirm end of utterance → Thinking (cortex runs whisper).
                stopListening()
                transitionTo(AppState.Thinking)
            }
            AppState.Card -> {
                if (currentCardOptions.isEmpty()) dismissCardLocally()
                else emitDecision("Approve")
            }
            AppState.Insight -> {
                // Insight engage → open mic with insight context (TODO: pass
                // insight_id as part of stream_id so Cortex correlates).
                val streamId = "insight_engage_${System.currentTimeMillis()}"
                startListening(streamId)
            }
            else -> Timber.v("StateMachine · primary click ignored in ${stateFlow.value}")
        }
    }

    /** Long-press of the right-temple button. */
    fun handlePrimaryLongPress() {
        when (stateFlow.value) {
            AppState.Card -> {
                // F2: info-only cards have no Modify semantic; LONG_PRESS is no-op there
                if (currentCardOptions.isNotEmpty()) emitDecision("Modify")
                else Timber.v("StateMachine · long-press on info-only card ignored")
            }
            AppState.Idle -> handlePrimaryClick()       // long-press = wake (same as click for now)
            else -> Timber.v("StateMachine · long-press ignored in ${stateFlow.value}")
        }
    }

    /** Double-click — system-occupied as "back"; we route to Kill / dismiss. */
    fun handlePrimaryDoubleClick() {
        when (stateFlow.value) {
            AppState.Card -> {
                if (currentCardOptions.isEmpty()) dismissCardLocally()
                else emitDecision("Kill")
            }
            AppState.Listening -> { stopListening(); transitionTo(AppState.Idle) }
            else -> transitionTo(AppState.Idle)
        }
    }

    fun handleTwoFingerTap() { /* CARD secondary action — reserved */ }
    fun handleTwoFingerDoubleTap() {
        // F2: info-only cards dismiss locally; actionable cards emit Kill
        if (stateFlow.value == AppState.Card) {
            if (currentCardOptions.isEmpty()) dismissCardLocally()
            else emitDecision("Kill")
        }
    }
    fun handleTwoFingerSwipeForward() { if (stateFlow.value == AppState.Card) hudRenderer.scrollCardDown() }
    fun handleTwoFingerSwipeBack() { if (stateFlow.value == AppState.Card) hudRenderer.scrollCardUp() }

    /**
     * F2 (2026-05-28): dismiss an info-only card without telling Cortex.
     * Used when the card has no actionable options (`options=[]`) — there's
     * no Approve/Modify/Kill decision for Cortex to act on; the user just
     * wants to clear the panel. Cancels the F3 TTL job in the process.
     */
    private fun dismissCardLocally() {
        cardTtlJob?.cancel()
        cardTtlJob = null
        currentCardOptions = emptyList()
        currentCardId = null
        transitionTo(AppState.Idle)
    }

    /**
     * R-13 / C-55: Cortex asks for a scene photo because the router routed
     * to a vision-aware tool but the user's voice invoke had no image. We
     * delegate the actual camera open to the Service (which has a Context for
     * CameraGate). The Service is expected to send back an ImageAttached
     * event with the same req_id when capture completes (or empty image on
     * failure — Cortex falls back gracefully).
     */
    private fun handleRequestImage(frame: JsonObject) {
        val reqId = frame["req_id"]?.jsonPrimitive?.contentOrNull
        if (reqId.isNullOrEmpty()) {
            Timber.w("StateMachine · request_image missing req_id")
            return
        }
        val hint = frame["hint"]?.jsonPrimitive?.contentOrNull
        Timber.i("StateMachine · request_image req_id=$reqId hint=$hint")
        val cb = onImageRequested
        if (cb == null) {
            Timber.w("StateMachine · request_image but no onImageRequested callback; ignoring (Cortex will timeout)")
            return
        }
        cb(reqId, hint)
    }

    private fun emitDecision(decision: String, feedbackText: String? = null) {
        val cardId = currentCardId ?: run {
            Timber.w("StateMachine · emitDecision($decision) but no currentCardId")
            return
        }
        val ev = GlassEvent.UserDecision(
            ts = Instant.now().toString(),
            payload = GlassEvent.UserDecision.Payload(
                cmdId = cardId,
                decision = decision,
                feedbackText = feedbackText,
            ),
        )
        val ok = wss.sendEvent(ev)
        Timber.i("StateMachine · user_decision($decision) for $cardId · sent=$ok")
        // Don't transition here — the server will respond with hud_state,
        // card, or no response (Approve / Kill terminal).
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
        // Leaving Insight cancels the auto-close timer.
        if (prev == AppState.Insight && next != AppState.Insight) {
            insightTtlJob?.cancel()
            insightTtlJob = null
        }
        // F3: leaving Card cancels the info-only TTL job (whether triggered
        // by local dismiss, user action, or a new state push from Cortex).
        if (prev == AppState.Card && next != AppState.Card) {
            cardTtlJob?.cancel()
            cardTtlJob = null
            // Don't clear currentCardOptions here — dismissCardLocally already
            // does it before calling us, and an inbound new card will repopulate.
        }
        hudRenderer.transition(prev, next)
        // TODO: Halo Ring profile push (when ring paired)
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
