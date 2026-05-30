package com.constellation.glass.glass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.constellation.glass.audio.AudioCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.sqrt

/**
 * Glass-flavor AudioCapture. Reads R08's 8-channel audio (algorithm-processed
 * + 4 raw mics + 2 echo refs) via `AudioRecord` with the Rokid-proprietary
 * channel mask, then **deinterleaves channel 0** (the iFlytek-processed
 * speech-optimized channel) for downstream consumption.
 *
 * Source: `reference/rokid-glass/bare-metal-docs/02-audio-recording.md`
 * + `reference/rokid-glass/cxrl-sample-android/.../AudioUsageViewModel.kt`.
 */
class GlassAudioCapture(
    private val ctx: Context,
    private val scope: CoroutineScope,
) : AudioCapture {

    companion object {
        /** Rokid proprietary 8-channel layout for R08 glass. */
        private const val ROKID_CHANNEL_MASK = 0x6000FC
        private const val CHANNELS_PER_FRAME = 8

        // ChannelMask 0x6000FC popcount = 8 channels. Layout:
        //   ch 0/1 : algorithm-processed (NR/AEC/beamforming via iFlytek)
        //   ch 2-5 : 4 raw mics
        //   ch 6/7 : hardware echo reference
        // We pull channel 0 only.
        private const val ALGO_CHANNEL = 0
    }

    private val _amplitude = MutableStateFlow(0f)
    override val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    override val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _chunks = MutableSharedFlow<ShortArray>(replay = 0, extraBufferCapacity = 8)
    override val chunks: SharedFlow<ShortArray> = _chunks.asSharedFlow()

    private var record: AudioRecord? = null
    private var captureJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        if (_isCapturing.value) {
            Timber.v("GlassAudioCapture · start — already running")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("GlassAudioCapture · RECORD_AUDIO not granted; cannot start")
            return
        }
        // getMinBufferSize doesn't accept arbitrary channel masks; use a
        // conservative 8-channel-equivalent size. CHUNK_SAMPLES samples per
        // channel × 8 channels × 2 bytes = ~64 KB per chunk, so we allocate
        // 4× for headroom.
        val bufBytes = AudioCapture.CHUNK_SAMPLES * CHANNELS_PER_FRAME * 2 * 4

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioCapture.SAMPLE_RATE)
                        // Rokid HAL outputs the 8-ch algo audio ONLY on the
                        // POSITIONAL channel-mask path (official sample uses
                        // setChannelMask, not setChannelIndexMask). The index
                        // path (0x80000000|mask) yielded an all-zero/silenced
                        // stream. See bare-metal-docs/02-audio-recording.md.
                        .setChannelMask(ROKID_CHANNEL_MASK)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (t: Throwable) {
            Timber.w(t, "GlassAudioCapture · AudioRecord construct failed (channel mask 0x6000FC)")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Timber.w("GlassAudioCapture · state=${rec.state}; not INITIALIZED")
            rec.release()
            return
        }
        record = rec
        try { rec.startRecording() } catch (t: Throwable) {
            Timber.w(t, "GlassAudioCapture · startRecording threw")
            rec.release()
            record = null
            return
        }
        _isCapturing.value = true
        Timber.i("GlassAudioCapture · started · 0x6000FC 8-ch → deinterleave ch$ALGO_CHANNEL")

        captureJob = scope.launch(Dispatchers.IO) {
            // 8-channel interleaved buffer: CHUNK_SAMPLES per-channel * 8 chans
            val interleaved = ShortArray(AudioCapture.CHUNK_SAMPLES * CHANNELS_PER_FRAME)
            val mono = ShortArray(AudioCapture.CHUNK_SAMPLES)
            try {
                while (isActive && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val r = record?.read(interleaved, 0, interleaved.size) ?: -1
                    if (r <= 0) {
                        if (r == 0) continue
                        Timber.w("GlassAudioCapture · read=$r — bailing")
                        break
                    }
                    val frames = r / CHANNELS_PER_FRAME
                    // Deinterleave channel ALGO_CHANNEL: take every 8th sample
                    // starting at offset ALGO_CHANNEL.
                    var sumSq = 0.0
                    for (i in 0 until frames) {
                        val s = interleaved[i * CHANNELS_PER_FRAME + ALGO_CHANNEL]
                        mono[i] = s
                        sumSq += s.toDouble() * s.toDouble()
                    }
                    if (frames > 0) {
                        val rms = sqrt(sumSq / frames)
                        _amplitude.value = (rms / 8_000.0).toFloat().coerceIn(0f, 1f)
                    }
                    _chunks.tryEmit(mono.copyOf(frames))
                }
            } catch (t: Throwable) {
                Timber.w(t, "GlassAudioCapture · loop crashed")
            } finally {
                _amplitude.value = 0f
            }
        }
    }

    override fun stop() {
        captureJob?.cancel()
        captureJob = null
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
        _isCapturing.value = false
        _amplitude.value = 0f
    }
}
