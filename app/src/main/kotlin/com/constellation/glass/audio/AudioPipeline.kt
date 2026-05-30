package com.constellation.glass.audio

import android.util.Base64
import com.constellation.glass.wss.GlassEvent
import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import kotlin.math.min

/**
 * Wraps a platform-specific [AudioCapture] and bridges its chunks to the WSS:
 *
 *   AudioCapture.start() → chunks flow → base64 encode → audio_chunk events
 *   AudioCapture.stop()  → audio_end event with duration
 *
 * Owns the per-utterance `stream_id` and monotonic `seq` counter. The capture
 * itself doesn't know about WSS; the pipeline doesn't know about AudioRecord
 * APIs or channel masks — clean split per v2.1.
 *
 * Also exposes the capture's [amplitude] StateFlow unchanged so the HUD can
 * subscribe for Level-1 g-wave visualization.
 */
class AudioPipeline(
    private val capture: AudioCapture,
    private val wss: WssClient,
    private val scope: CoroutineScope,
) {

    private var collectJob: Job? = null
    private var streamId: String? = null
    private var seq: Int = 0
    private var startNanos: Long = 0L

    /** Mirror of [AudioCapture.isCapturing]. */
    val isCapturing: StateFlow<Boolean> get() = capture.isCapturing

    /** Mirror of [AudioCapture.amplitude]. */
    val amplitude: StateFlow<Float> get() = capture.amplitude

    /** Begin capture for [streamId]. Idempotent — re-calling with the same id
     *  is a no-op while capture is alive. */
    fun start(streamId: String, langHint: String? = null) {
        if (this.streamId == streamId && capture.isCapturing.value) {
            Timber.v("AudioPipeline · start($streamId) — already capturing")
            return
        }
        if (capture.isCapturing.value) stop()

        this.streamId = streamId
        this.seq = 0
        this.startNanos = System.nanoTime()

        // Bind THIS run's stream_id into the collector (Zack 2026-05-30, #1).
        // A just-cancelled previous collectJob can still deliver a straggler
        // chunk from the SharedFlow buffer; if sendChunk read the *member*
        // streamId it would tag that stale audio with the NEW stream_id and the
        // 2nd utterance would carry leftovers of the 1st. Capturing the id
        // locally tags a straggler with its OWN (old, already-finalized) stream
        // — Cortex drops it — so the new stream starts clean.
        val sidForRun = streamId
        collectJob = scope.launch {
            capture.chunks.collect { chunk -> sendChunk(chunk, sidForRun) }
        }
        capture.start()
        Timber.i("AudioPipeline · capture started · streamId=$streamId · langHint=$langHint")
    }

    /** Stop capture and emit audio_end. Idempotent. */
    fun stop() {
        val sid = streamId
        capture.stop()
        collectJob?.cancel()
        collectJob = null

        if (sid != null) {
            val durMs = ((System.nanoTime() - startNanos) / 1_000_000L)
                .let { min(it, Int.MAX_VALUE.toLong()).toInt() }
            val ok = wss.sendEvent(
                GlassEvent.AudioEnd(
                    ts = Instant.now().toString(),
                    payload = GlassEvent.AudioEnd.Payload(
                        streamId = sid,
                        durationMs = durMs,
                        langHint = null,
                    ),
                )
            )
            Timber.i("AudioPipeline · stop · streamId=$sid · seq=$seq · ${durMs}ms · audio_end_sent=$ok")
        }
        streamId = null
        seq = 0
    }

    private fun sendChunk(chunk: ShortArray, sid: String) {
        // ShortArray (mono PCM samples) → little-endian byte[] → base64
        val pcmBytes = ByteArray(chunk.size * 2)
        for (i in chunk.indices) {
            val s = chunk[i].toInt()
            pcmBytes[i * 2] = (s and 0xff).toByte()
            pcmBytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
        }
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val event = GlassEvent.AudioChunk(
            ts = Instant.now().toString(),
            payload = GlassEvent.AudioChunk.Payload(
                streamId = sid,
                seq = seq++,
                b64Pcm = b64,
                sampleRate = AudioCapture.SAMPLE_RATE,
                channels = AudioCapture.CHANNELS,
            ),
        )
        val sent = wss.sendEvent(event)
        if (!sent) Timber.v("AudioPipeline · audio_chunk seq=${event.payload.seq} dropped (WSS down)")
    }
}
