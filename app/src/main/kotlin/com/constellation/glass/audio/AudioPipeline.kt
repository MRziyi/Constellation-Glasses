package com.constellation.glass.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.constellation.glass.wss.GlassEvent
import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.Instant
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Mic capture → 250ms PCM chunks → audio_chunk events.
 *
 * Owned by ConstellationService, driven by StateMachine on mic_open / mic_close.
 *
 * Level 1 streaming feedback: per-chunk RMS amplitude is exposed via
 * [amplitude] (StateFlow<Float> in 0..1) so the HUD g-wave can pulse with the
 * user's voice. Zero server round-trips for this — pure local visual.
 *
 * Format: 16 kHz, mono, 16-bit signed PCM. Chunk size: 4000 samples = 8000
 * bytes = 250 ms. Four chunks ≈ 1 s, which is also the cadence Cortex Level 2
 * uses to fire partial Whisper passes.
 */
class AudioPipeline(
    private val ctx: Context,
    private val wss: WssClient,
    private val scope: CoroutineScope,
) {

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        // 4000 samples * 2 bytes = 8000 bytes = 250 ms.
        const val CHUNK_SAMPLES = 4_000
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    }

    private val _amplitude = MutableStateFlow(0f)
    /** 0..1, smoothed-ish RMS for the most recent chunk. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var record: AudioRecord? = null
    private var captureJob: Job? = null
    private var streamId: String? = null
    private var seq: Int = 0
    private var startNanos: Long = 0L

    /** True if [start] was called and the capture loop is alive. */
    val isCapturing: Boolean get() = captureJob?.isActive == true

    /** Begin capture. Idempotent — re-calling with the same stream is a no-op. */
    @SuppressLint("MissingPermission")
    fun start(streamId: String, langHint: String? = null) {
        if (this.streamId == streamId && isCapturing) {
            Timber.v("AudioPipeline · start($streamId) — already capturing")
            return
        }
        if (isCapturing) stop()

        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("AudioPipeline · RECORD_AUDIO not granted; cannot start")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Timber.w("AudioPipeline · getMinBufferSize returned $minBuf — bad config")
            return
        }
        // 4× chunk to absorb scheduling jitter.
        val bufBytes = maxOf(minBuf, CHUNK_BYTES * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (t: Throwable) {
            Timber.w(t, "AudioPipeline · AudioRecord construct failed")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Timber.w("AudioPipeline · AudioRecord state=${rec.state}, not INITIALIZED")
            rec.release()
            return
        }

        this.streamId = streamId
        this.seq = 0
        this.startNanos = System.nanoTime()
        record = rec
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Timber.w(t, "AudioPipeline · startRecording threw")
            rec.release()
            record = null
            return
        }
        Timber.i("AudioPipeline · capture started · streamId=$streamId · langHint=$langHint")

        captureJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(CHUNK_SAMPLES)
            try {
                while (isActive && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val r = record?.read(buf, 0, CHUNK_SAMPLES) ?: -1
                    if (r <= 0) {
                        if (r == 0) continue
                        Timber.w("AudioPipeline · read=$r — bailing capture loop")
                        break
                    }
                    val n = r
                    // RMS for Level 1 g-wave amplitude. We normalize roughly:
                    // short range is ±32767; quiet speech ~2000, loud ~10000.
                    // Divide by 8000 and clamp to 1.0 → matches the design's
                    // "modest gain so even soft speech moves the line".
                    var sumSq = 0.0
                    for (i in 0 until n) {
                        val s = buf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / n)
                    _amplitude.value = (rms / 8_000.0).toFloat().coerceIn(0f, 1f)

                    // PCM short[n] → little-endian byte[2n] → base64
                    val pcmBytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        val s = buf[i].toInt()
                        pcmBytes[i * 2] = (s and 0xff).toByte()
                        pcmBytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                    }
                    val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)

                    val event = GlassEvent.AudioChunk(
                        ts = Instant.now().toString(),
                        payload = GlassEvent.AudioChunk.Payload(
                            streamId = streamId,
                            seq = seq++,
                            b64Pcm = b64,
                            sampleRate = SAMPLE_RATE,
                            channels = CHANNELS,
                        ),
                    )
                    val ok = wss.sendEvent(event)
                    if (!ok) Timber.v("AudioPipeline · audio_chunk seq=${event.payload.seq} dropped (WSS down)")
                }
            } catch (t: Throwable) {
                Timber.w(t, "AudioPipeline · capture loop crashed")
            } finally {
                _amplitude.value = 0f
            }
        }
    }

    /** Stop capture and emit audio_end. Idempotent. */
    fun stop() {
        val sid = streamId
        val job = captureJob
        val rec = record
        captureJob = null
        record = null

        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        job?.cancel()
        _amplitude.value = 0f

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
            Timber.i("AudioPipeline · stop · streamId=$sid · seq=$seq · ${durMs}ms · sent=$ok")
        }
        streamId = null
        seq = 0
    }
}
