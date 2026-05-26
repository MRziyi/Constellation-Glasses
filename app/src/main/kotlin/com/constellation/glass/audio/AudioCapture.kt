package com.constellation.glass.audio

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-specific microphone capture. Implementations:
 *  - `GlassAudioCapture` (glass flavor) — AudioRecord with `ChannelMask=0x6000FC`
 *    on R08; reads 8-channel interleaved 16kHz/16-bit PCM, deinterleaves channel
 *    0 (algorithm-processed audio with iFlytek NR/AEC) and emits as mono.
 *  - `PhoneAudioCapture` (phoneDebug flavor) — AudioRecord with
 *    `CHANNEL_IN_MONO` on a regular phone.
 *
 * Both emit 250 ms chunks of mono 16 kHz 16-bit PCM via [chunks].
 *
 * Contract:
 *  - 16000 Hz, mono, signed 16-bit
 *  - Chunk size: 4000 samples = 8000 bytes = 250 ms
 *  - [start] is idempotent; [stop] is idempotent
 *  - On permission denial, [start] logs and is a no-op (capture flag stays false)
 *  - Capture runs on a background dispatcher; consumers must not block in the
 *    collector
 */
interface AudioCapture {

    /** Begin capture. Idempotent. */
    fun start()

    /** Stop capture. Idempotent. */
    fun stop()

    /** True while capture is alive. */
    val isCapturing: StateFlow<Boolean>

    /**
     * 250 ms chunks of mono PCM (4000 short samples each). Emitted as
     * [ShortArray] to avoid byte/short conversions in the hot path; the
     * pipeline encodes to little-endian bytes + base64 in one place.
     */
    val chunks: SharedFlow<ShortArray>

    /**
     * 0..1 RMS amplitude of the most recent chunk. Drives the on-HUD g-wave
     * visualization (Level 1 streaming feedback). Updated at chunk cadence
     * (~4 Hz at 250 ms chunks).
     */
    val amplitude: StateFlow<Float>

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val CHUNK_SAMPLES = 4_000     // 250 ms
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    }
}
