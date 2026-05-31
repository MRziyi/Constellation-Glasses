package com.constellation.glass.wss

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire schema for the Glass ↔ Cortex WSS channel.
 *
 * Two top-level kinds: events (Glass → Cortex) and commands (Cortex → Glass).
 * Each frame is a JSON object with `kind` discriminating the type. The Glass
 * declares its accepted command kinds in the connect handshake; older
 * (Console) clients ignore the glass-shaped ones.
 *
 * See GLASS-CLIENT-DESIGN.md §4 for the full list + payload shapes.
 */

// ── Events: Glass → Cortex ──────────────────────────────────────────────

/**
 * Wire format: `{kind, ts, payload: {...}}` with snake_case keys inside the
 * payload, matching cortex.schema.Event. Each subclass is a self-contained
 * Serializable so kotlinx.serialization writes the right top-level + payload.
 */
@Serializable
sealed class GlassEvent {
    abstract val kind: String
    abstract val ts: String     // ISO 8601 timestamp

    @Serializable
    data class UserInvoke(
        override val ts: String,
        val payload: Payload,
    ) : GlassEvent() {
        override val kind: String = "user_invoke"

        @Serializable
        data class Payload(
            val text: String,
            @SerialName("session_id") val sessionId: String? = null,
            val image: String? = null,
        )
    }

    @Serializable
    data class UserDecision(
        override val ts: String,
        val payload: Payload,
    ) : GlassEvent() {
        override val kind: String = "user_decision"

        @Serializable
        data class Payload(
            @SerialName("in_reply_to") val cmdId: String,
            val decision: String,           // "Approve" | "Modify" | "Kill"
            @SerialName("feedback_text") val feedbackText: String? = null,
        )
    }

    @Serializable
    data class AudioChunk(
        override val ts: String,
        val payload: Payload,
    ) : GlassEvent() {
        override val kind: String = "audio_chunk"

        @Serializable
        data class Payload(
            @SerialName("stream_id") val streamId: String,
            val seq: Int,
            @SerialName("b64_pcm") val b64Pcm: String,
            @SerialName("sample_rate") val sampleRate: Int = 16000,
            val channels: Int = 1,
        )
    }

    @Serializable
    data class AudioEnd(
        override val ts: String,
        val payload: Payload,
    ) : GlassEvent() {
        override val kind: String = "audio_end"

        @Serializable
        data class Payload(
            @SerialName("stream_id") val streamId: String,
            @SerialName("duration_ms") val durationMs: Int,
            @SerialName("lang_hint") val langHint: String? = null,
        )
    }

    @Serializable
    data class DecisionVoice(
        override val ts: String,
        val payload: Payload,
    ) : GlassEvent() {
        override val kind: String = "decision_voice"

        @Serializable
        data class Payload(
            @SerialName("cmd_id") val cmdId: String,
            val command: String,            // approve | modify | kill | scroll_up | ...
        )
    }

    /**
     * R-13 / C-55: reply to a Cortex `request_image` frame with the captured
     * scene. Glass-side handler: `StateMachine.dispatch` `"request_image"`
     * route → `CameraGate.captureViaGate` → `wss.sendEvent(this)`.
     *
     * Two transports (Zack 2026-05-31):
     *  - binary (default for a real photo): this event is a HEADER announcing
     *    `binary=true` + `mime` + `bytes_len`; the raw JPEG bytes follow as the
     *    very next binary WS frame. No base64 +33%, no multi-MB JSON string.
     *  - base64 (legacy / failure): `image` carries the b64 JPEG, or "" to
     *    signal "tried but failed" → Cortex falls back to image-less dispatch.
     */
    @Serializable
    data class ImageAttached(
        override val ts: String,
        val payload: Payload,
    ) : GlassEvent() {
        override val kind: String = "image_attached"

        @Serializable
        data class Payload(
            @SerialName("req_id") val reqId: String,
            val image: String? = null,          // base64 JPEG (legacy); "" on capture failure
            val binary: Boolean = false,        // true → raw bytes follow as the next binary frame
            val mime: String? = null,           // e.g. "image/jpeg" (binary transport)
            @SerialName("bytes_len") val bytesLen: Int? = null,
        )
    }
}

// ── Commands: Cortex → Glass ────────────────────────────────────────────

@Serializable
sealed class CortexCommand {
    abstract val kind: String

    @Serializable
    data class Progress(
        override val kind: String = "progress",
        val stage: String,
        val icon: String? = null,
        val detail: String? = null,
        val sessionId: String? = null,
    ) : CortexCommand()

    @Serializable
    data class HudState(
        override val kind: String = "hud_state",
        val stage: String,
        val icon: String? = null,
        val detailRuns: List<StyledRun> = emptyList(),
        val metaRuns: List<StyledRun> = emptyList(),
    ) : CortexCommand()

    @Serializable
    data class Card(
        override val kind: String = "card",
        val id: String,
        val titleRuns: List<StyledRun>,
        val bodyRuns: List<StyledRun>,
        val scrollTotalLines: Int = 0,
        val options: List<String> = listOf("approve", "modify", "kill"),
        val ttlMs: Int = 30_000,
    ) : CortexCommand()

    @Serializable
    data class Insight(
        override val kind: String = "insight",
        val titleRuns: List<StyledRun>,
        val bodyRuns: List<StyledRun>,
        val insightKind: String,
        val ttlMs: Int = 8_000,
        val contextId: String? = null,
    ) : CortexCommand()

    @Serializable
    data class MicOpen(
        override val kind: String = "mic_open",
        val streamId: String,
        val langHint: String? = null,
        val ttlMs: Int? = null,
    ) : CortexCommand()

    @Serializable
    data class MicClose(
        override val kind: String = "mic_close",
        val streamId: String,
    ) : CortexCommand()

    /** Generic catch-all for kinds Glass doesn't specifically handle. Lets us
     *  log unknowns without crashing. */
    @Serializable
    data class Raw(
        override val kind: String,
        val payload: JsonObject? = null,
    ) : CortexCommand()
}

/** Styled-run for HUD bodies. Server parses markdown into these. */
@Serializable
data class StyledRun(
    val text: String,
    /** "normal" | "bold" | "italic" | "bold_italic" | "code" | "dim" */
    val style: String = "normal",
)
