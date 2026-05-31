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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
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
    private val onImageRequested: ((reqId: String, hint: String?, tier: String) -> Unit)? = null,
    /**
     * Voice-driven shortcut-slot config (Zack's model). Cortex parses a
     * "set shortcut N to …" utterance and emits a `shortcut_config` frame;
     * we delegate the local-slot write to the Service (needs a Context for
     * [com.constellation.glass.ShortcutSlots]). Null args = leave unchanged.
     */
    private val onShortcutConfig: ((slot: Int, prompt: String?, sendPhoto: Boolean?, label: String?, tier: String?) -> Unit)? = null,
    /**
     * Auth expired (Edge 401/403, reconnect halted). The wearer LONG-presses the
     * AuthExpired HUD to open the camera scanner and re-pair; the Service owns
     * the Activity launch (needs a Context). Null in tests / non-UI builds.
     */
    private val onRePairRequested: (() -> Unit)? = null,
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
    /** The 3 formal card types (server `card_type`): "notification" (dismiss
     *  only), "checkpoint" (approve/modify/reject|kill), "question" (single
     *  answer → mic). Drives gesture→decision mapping below. */
    private var currentCardType: String = "checkpoint"

    /** Retained only so stopListening can cancel a stale job — the mic no longer
     *  auto-closes on a timer (Zack 2026-05-31: every mic ends on the wearer's
     *  TAP, no cap, fresh-listen and answer/modify alike). */
    private var micWatchdogJob: Job? = null
    /** Delay before AudioRecord starts, so the MicGate foreground Activity
     *  reaches RESUMED first (AppOps RECORD_AUDIO is checked at startRecording). */
    private val micGateWarmupMs = 450L

    /** Start collecting inbound + connection-state. Called once from
     *  ConstellationService.onStartCommand. */
    @OptIn(FlowPreview::class)
    fun bind() {
        scope.launch {
            wss.inbound.collect { frame -> dispatch(frame) }
        }
        // Connection + auth gate. authExpired (401/403, halted) takes priority
        // over a plain network drop: it routes to AuthExpired (re-pair) instead
        // of Offline (auto-retry). A manual dismiss (double-tap → Idle) sticks
        // because combine only re-fires on a flow change, not on our transition.
        scope.launch {
            kotlinx.coroutines.flow.combine(wss.connected, wss.authExpired) { c, a -> c to a }
                .collect { (connected, authExpired) ->
                    val st = stateFlow.value
                    when {
                        authExpired -> if (st != AppState.AuthExpired) {
                            Timber.i("StateMachine · auth expired → AuthExpired")
                            transitionTo(AppState.AuthExpired)
                        }
                        !connected -> if (st != AppState.Offline && st != AppState.AuthExpired) {
                            Timber.i("StateMachine · WSS down → Offline")
                            transitionTo(AppState.Offline)
                        }
                        else -> if (st == AppState.Offline || st == AppState.AuthExpired) {
                            Timber.i("StateMachine · WSS up → Idle")
                            transitionTo(AppState.Idle)
                        }
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
            "shortcut_config" -> handleShortcutConfig(frame)
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

        // "completed" = the server's turn-DONE signal (end of _execute_remaining,
        // after an approve→execute). The turn is over → return to Idle (slide-up
        // exit) instead of staying parked in Thinking showing "✓ done" forever.
        // This is the real fix for the "done card has no exit method" report
        // (Rev 18): an approve on an agent card lands on Thinking (C-76) while the
        // action runs, then this closes the HUD when it finishes.
        if (stage == "completed") {
            transitionTo(AppState.Idle)
            return
        }

        // Level 2: server-emitted partial transcripts arrive as
        // hud_state(stage="listening", detail_runs=[<partial>]). Only refresh
        // the live text WHILE we're still in Listening. A late partial that
        // arrives AFTER the wearer TAPped to stop (we're now on the STT-review
        // loading card) MUST NOT flip the HUD back to Listening — that one-frame
        // flip was the spurious "listening" flash before the STT-review card
        // (Zack 2026-05-30). Never transition INTO Listening from here.
        if (stage == "listening") {
            if (stateFlow.value == AppState.Listening) {
                partialRuns = detailRuns
                hudRenderer.updateListening(
                    elapsedSec = 0,
                    amplitude = 0f,
                    partialRuns = detailRuns,
                )
            }
            return  // stale partials (state != Listening) are dropped
        }
        if (stateFlow.value == AppState.Listening) {
            // Non-listening hud_state while still listening — refresh in place,
            // don't bounce to Thinking mid-utterance.
            partialRuns = detailRuns
            hudRenderer.updateListening(elapsedSec = 0, amplitude = 0f, partialRuns = detailRuns)
            return
        }

        // (The glass-side "drop progress while a card is up" guard was REMOVED —
        // Zack 2026-05-30. Ordering is now enforced at the SOURCE: cortex's
        // unified outbox parks its sender after a decision card, so no progress
        // is even sent while a decision is pending. Nothing is dropped here — a
        // hud_state that does arrive is legitimately the next thing to show.)
        if (stateFlow.value !in setOf(AppState.Thinking, AppState.Listening)) {
            transitionTo(AppState.Thinking)
        }
        hudRenderer.updateThinking(icon, detailRuns, metaRuns)
    }

    private fun handleCard(frame: JsonObject) {
        transitionTo(AppState.Card)
        val cardId = frame["cmd_id"]?.jsonPrimitive?.contentOrNull ?: "?"
        currentCardId = cardId
        val echoRuns = frame["echo_runs"]?.let { jsonToOrg(it) }
        val titleRuns = frame["title_runs"]?.let { jsonToOrg(it) }
        val bodyRuns = frame["body_runs"]?.let { jsonToOrg(it) }
        val options = frame["options"]?.let { el ->
            try {
                el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
            } catch (_: Throwable) { emptyList() }
        } ?: listOf("approve", "modify", "kill")
        currentCardOptions = options
        currentCardType = frame["card_type"]?.jsonPrimitive?.contentOrNull
            ?: if (options.isEmpty()) "notification" else "checkpoint"
        hudRenderer.showCard(cardId, titleRuns, bodyRuns, options, echoRuns)
        // No TTL auto-close: the card stays until a ring gesture acts on it.
        // Gesture→decision depends on card_type (see handlePrimary* below):
        //   notification → TAP/DOUBLE dismiss locally
        //   checkpoint   → TAP=approve · LONG=modify · DOUBLE=reject/kill
        //   question     → TAP=answer (→ mic) · DOUBLE=dismiss
        // Both actionable and info-only cards persist — the wearer dismisses on
        // their own schedule via the ring (Doc/18 §5 profile takeover).
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
        // ttlSec=0 → no countdown / no auto-close. Insight stays until the
        // wearer engages (ring TAP → mic) or dismisses (ring DOUBLE_TAP → Idle).
        hudRenderer.showInsight(titleRuns, bodyRuns, ttlSec = 0)
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
        // Entering Listening makes the Service launch the MicGate foreground
        // Activity (AppOps RECORD_AUDIO:foreground). AppOps is evaluated at
        // startRecording, so wait for the gate to reach RESUMED before opening
        // the mic — otherwise YodaOS records pure silence (peak=0).
        scope.launch {
            delay(micGateWarmupMs)
            if (stateFlow.value == AppState.Listening) audio?.start(streamId, langHint)
        }
        // The mic ends ONLY on the wearer's TAP — no timeout at all (Zack
        // 2026-05-31: utterances can be long; the wearer is fully in control,
        // fresh-listen and answer/modify alike). Cancel any stale watchdog.
        micWatchdogJob?.cancel()
        micWatchdogJob = null
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
                // Confirm end of utterance → IMMEDIATELY turn the listening page
                // into the STT-review page in a loading state (Zack 2026-05-30,
                // #3 — no generic Thinking middle-step, no spinner-on-listening).
                // Cortex's real stt_review card (cmd_id + transcript) replaces
                // this the moment Whisper finishes.
                stopListening()
                showSttReviewLoading()
            }
            AppState.Card -> when (currentCardType) {
                // notification → OK: acknowledge locally (no Cortex flow is
                // waiting on it). "OK", never "dismiss" — a flow ends only as
                // OK (approved) or kill (Zack 2026-05-30).
                "notification" -> ackCardLocally()
                // question → the single "answer" action: emit it; Cortex opens
                // the mic (answer_<cmd_id>) and we capture the spoken reply,
                // which Cortex transcribes + types into CC's "Other" slot.
                "question" -> emitDecision("answer")
                // stt_review → APPROVE the transcript; Cortex now (and ONLY now)
                // routes it downstream per its original intent (STT-review gate).
                "stt_review" -> emitDecision("approve")
                // checkpoint → APPROVE (first option: approve)
                else -> emitDecision(currentCardOptions.firstOrNull() ?: "approve")
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

    /** Long-press of the right-temple button.
     *
     *  LONG_PRESS is ALWAYS "重讲" / re-speak (Zack 2026-05-30): on any card the
     *  wearer who's unhappy with the result just long-presses to say it again.
     *  The downstream differs by card type but the gesture meaning is uniform:
     *    - checkpoint → emit `modify`: re-speak that EDITS the pending proposal
     *      (Cortex re-plans WITH the prior plan as context)
     *    - question   → emit `answer`: the answer is spoken anyway, so re-speak
     *      == answer (opens the mic on the answer_<cmd_id> stream)
     *    - notification / info / plain reply → discard the result and start a
     *      brand-new ask (no prior plan to edit) */
    fun handlePrimaryLongPress() {
        when (stateFlow.value) {
            AppState.Card -> when (currentCardType) {
                "checkpoint" ->
                    if (currentCardOptions.any { it.equals("modify", ignoreCase = true) }) {
                        emitDecision("modify")
                    } else {
                        redoFreshListening()
                    }
                "question" -> emitDecision("answer")
                // stt_review → REDO: Cortex re-opens the right mic (preserving
                // the original intent) so the wearer re-speaks. Not a local
                // fresh listen — modify/answer redos must return to their card.
                "stt_review" -> emitDecision("redo")
                // notification = a FINAL report (its input was already
                // STT-reviewed before processing). It ONLY acknowledges (OK) —
                // a finished result is never re-spoken (Zack 2026-05-30, #4).
                "notification" -> ackCardLocally()
                else -> redoFreshListening()
            }
            AppState.Idle -> handlePrimaryClick()       // long-press = wake (same as click for now)
            // AuthExpired: LONG = open the camera scanner to re-pair (Zack 2026-05-31).
            AppState.AuthExpired -> {
                Timber.i("StateMachine · re-pair requested (long-press on AuthExpired)")
                onRePairRequested?.invoke()
            }
            else -> Timber.v("StateMachine · long-press ignored in ${stateFlow.value}")
        }
    }

    /**
     * "重讲" for cards with no pending proposal to edit (notification / info /
     * plain reply). The wearer is unhappy with THIS result — often a
     * mis-transcription (base partial ≠ small final, see whisper two-model
     * path) — so we drop the card and open the mic for a brand-new ask, exactly
     * like a fresh wake from Idle. (checkpoint long-press uses `modify` instead,
     * which re-plans WITH the prior proposal as context.)
     */
    private fun redoFreshListening() {
        Timber.i("StateMachine · redo (long-press) — discard card, fresh re-speak")
        currentCardOptions = emptyList()
        currentCardType = "checkpoint"
        currentCardId = null
        startListening("fresh_${System.currentTimeMillis()}")
    }

    /**
     * #3 (Zack 2026-05-30): on utterance-end, turn the page into the STT-review
     * card in a LOADING state immediately. currentCardId stays null until
     * Cortex's real stt_review card (cmd_id + transcript) arrives and replaces
     * this — so every gesture no-ops meanwhile (emitDecision needs a card id;
     * you can't approve an empty transcript).
     */
    private fun showSttReviewLoading() {
        currentCardType = "stt_review"
        currentCardId = null
        currentCardOptions = listOf("approve", "redo")
        transitionTo(AppState.Card)
        val title = JSONArray().put(JSONObject().put("text", "STT review").put("style", "bold"))
        val body = JSONArray().put(JSONObject().put("text", "Transcribing…").put("style", "normal"))
        hudRenderer.showCard("stt_loading", title, body, currentCardOptions, null)
    }

    /** Double-click = KILL — terminate the flow (Zack 2026-05-30). Uniform across
     *  every card type: a flow ends only as OK (TAP) or kill (double-tap). There
     *  is NO dismiss. */
    fun handlePrimaryDoubleClick() {
        when (stateFlow.value) {
            AppState.Card -> when (currentCardType) {
                // notification → no Cortex flow is waiting; "kill" just clears it
                // locally (same effect as OK — nothing in-flight to terminate).
                "notification" -> ackCardLocally()
                // checkpoint → its terminal option (reject for permission, kill
                // for an agent card) — that IS the kill.
                "checkpoint" -> emitDecision(currentCardOptions.lastOrNull() ?: "kill")
                // question / stt_review → KILL the waiting flow: Cortex drops the
                // pending entry + terminates (never a silent local discard, which
                // would dangle the flow).
                else -> emitDecision("kill")
            }
            AppState.Listening -> { stopListening(); transitionTo(AppState.Idle) }
            else -> transitionTo(AppState.Idle)
        }
    }

    fun handleTwoFingerTap() { /* CARD secondary action — reserved */ }
    fun handleTwoFingerDoubleTap() {
        // Mirror DOUBLE_TAP = KILL (Zack 2026-05-30): notification → OK (nothing
        // in-flight); checkpoint → terminal option; question/stt_review → kill
        // the waiting flow via Cortex. No dismiss.
        if (stateFlow.value == AppState.Card) when (currentCardType) {
            "notification" -> ackCardLocally()
            "checkpoint" -> emitDecision(currentCardOptions.lastOrNull() ?: "kill")
            else -> emitDecision("kill")
        }
    }
    fun handleTwoFingerSwipeForward() { if (stateFlow.value == AppState.Card) hudRenderer.scrollCardDown() }
    fun handleTwoFingerSwipeBack() { if (stateFlow.value == AppState.Card) hudRenderer.scrollCardUp() }

    /**
     * "OK" — acknowledge a notification card locally (Zack 2026-05-30, renamed
     * from dismissCardLocally). ONLY notification cards reach here: Cortex sends
     * them fire-and-forget (no pending decision), so clearing the panel needs no
     * server message and dangles nothing. checkpoint/question/stt_review never
     * use this — they end via an explicit approve/answer/kill decision so their
     * waiting Cortex flow is always resolved. There is no "dismiss" anywhere.
     */
    private fun ackCardLocally() {
        currentCardOptions = emptyList()
        currentCardType = "checkpoint"
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
        val tier = frame["tier"]?.jsonPrimitive?.contentOrNull ?: "standard"
        Timber.i("StateMachine · request_image req_id=$reqId hint=$hint tier=$tier")
        val cb = onImageRequested
        if (cb == null) {
            Timber.w("StateMachine · request_image but no onImageRequested callback; ignoring (Cortex will timeout)")
            return
        }
        cb(reqId, hint, tier)
    }

    /**
     * Voice-driven shortcut-slot update. Cortex parsed a "set shortcut N to …"
     * utterance into `{slot, prompt?, send_photo?, label?}`; we hand it to the
     * Service to persist into [com.constellation.glass.ShortcutSlots]. Cortex
     * sends a separate info-only `card` as the user-visible confirmation.
     */
    private fun handleShortcutConfig(frame: JsonObject) {
        val slot = frame["slot"]?.jsonPrimitive?.intOrNull
        if (slot == null) {
            Timber.w("StateMachine · shortcut_config missing slot")
            return
        }
        val prompt = frame["prompt"]?.jsonPrimitive?.contentOrNull
        val sendPhoto = frame["send_photo"]?.jsonPrimitive?.booleanOrNull
        val label = frame["label"]?.jsonPrimitive?.contentOrNull
        val tier = frame["tier"]?.jsonPrimitive?.contentOrNull
        Timber.i("StateMachine · shortcut_config slot=$slot photo=$sendPhoto label=$label tier=$tier")
        onShortcutConfig?.invoke(slot, prompt, sendPhoto, label, tier)
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
        // Clear the card on decision so the wearer sees their action register
        // immediately. A modify/answer (no text yet) opens the mic instead →
        // keep the card up; the server's mic_open drives the next state.
        val d = decision.lowercase()
        val opensMic = (d == "modify" || d == "answer") && feedbackText == null
        if (!opensMic) {
            // Where to land matters for the EXIT ANIMATION (Zack 2026-05-30):
            // the overlay window slides UP and detaches only on →Idle. A
            // decision that CONTINUES the flow (approve / answer / modify-with-
            // text → the agent keeps working and a progress/result card follows)
            // must NOT bounce to Idle: that tears the window down (slide-up) and
            // immediately rebuilds it for the next card — a churn, not the
            // "卡→卡 = 内容替换、不算退出" the design wants. Go to Thinking so the
            // window stays and the continuation flows in as a content swap.
            // Only TERMINAL decisions (reject / kill — nothing follows) →Idle for
            // the clean slide-up exit. approve/answer/modify CONTINUE → Thinking;
            // the turn closes later via the server's stage="completed" frame
            // (handleHudState → Idle), so an agent card's approve doesn't churn
            // the window (Card→Idle→Thinking) — it stays Card→Thinking→Idle.
            val terminal = d == "reject" || d == "kill"
            currentCardOptions = emptyList()
            currentCardType = "checkpoint"
            currentCardId = null
            transitionTo(if (terminal) AppState.Idle else AppState.Thinking)
        }
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
        // Halo Ring profile push/pop is driven by ConstellationService observing
        // the state flow (it owns the Context needed for the broadcast): any
        // non-Idle/Offline state pushes `constellation_hud`; Idle/Offline pops.
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
