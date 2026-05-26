package com.constellation.glass.phonedebug

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
 * phoneDebug AudioCapture. Standard Android `AudioRecord` with mono input;
 * good enough to feed the WSS protocol verification. Glass flavor uses a
 * different impl with Rokid's `ChannelMask=0x6000FC` 8-channel mask.
 */
class PhoneAudioCapture(
    private val ctx: Context,
    private val scope: CoroutineScope,
) : AudioCapture {

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
            Timber.v("PhoneAudioCapture · start — already running")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("PhoneAudioCapture · RECORD_AUDIO not granted; cannot start")
            return
        }
        val minBuf = AudioRecord.getMinBufferSize(
            AudioCapture.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Timber.w("PhoneAudioCapture · getMinBufferSize=$minBuf — bad config")
            return
        }
        val bufBytes = maxOf(minBuf, AudioCapture.CHUNK_BYTES * 4)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioCapture.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (t: Throwable) {
            Timber.w(t, "PhoneAudioCapture · AudioRecord construct failed")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Timber.w("PhoneAudioCapture · AudioRecord state=${rec.state}")
            rec.release()
            return
        }
        record = rec
        try { rec.startRecording() } catch (t: Throwable) {
            Timber.w(t, "PhoneAudioCapture · startRecording threw")
            rec.release()
            record = null
            return
        }
        _isCapturing.value = true
        Timber.i("PhoneAudioCapture · started")

        captureJob = scope.launch(Dispatchers.IO) {
            val buf = ShortArray(AudioCapture.CHUNK_SAMPLES)
            try {
                while (isActive && record?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val r = record?.read(buf, 0, buf.size) ?: -1
                    if (r <= 0) {
                        if (r == 0) continue
                        Timber.w("PhoneAudioCapture · read=$r — bailing")
                        break
                    }
                    // RMS amplitude for Level-1 g-wave.
                    var sumSq = 0.0
                    for (i in 0 until r) {
                        val s = buf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / r)
                    _amplitude.value = (rms / 8_000.0).toFloat().coerceIn(0f, 1f)

                    _chunks.tryEmit(buf.copyOf(r))
                }
            } catch (t: Throwable) {
                Timber.w(t, "PhoneAudioCapture · loop crashed")
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
