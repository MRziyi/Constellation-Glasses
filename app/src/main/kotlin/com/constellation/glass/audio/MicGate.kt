package com.constellation.glass.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.delay
import timber.log.Timber

/**
 * A transparent foreground Activity that stays RESUMED for the duration of a
 * voice-listening session, so AppOps `RECORD_AUDIO: foreground` is satisfied
 * even when the app UI is HOMEd.
 *
 * **Why this exists** (2026-05-30): On Rokid Glasses (YodaOS-Sprite) the mic has
 * the exact same gate as the camera (C-51): a backgrounded-Service `AudioRecord`
 * open is **denied** — it reads pure silence (`peak=0, rms=0`) — even with
 * `foregroundServiceType="microphone"` declared AND passed to startForeground.
 * AppOps shows `RECORD_AUDIO: foreground` with a fresh `rejectTime` whenever the
 * UI is in the background. YodaOS strictly requires a RESUMED Activity in our
 * process. Diagnosed from a question-card answer: 12s of audio → whisper got 3
 * chars ("you" = a silence hallucination) because every sample was zero.
 *
 * Unlike [com.constellation.glass.camera.CameraGate] (one-shot ~2s capture), the
 * mic records for a variable time (until the wearer TAPs to stop), so this
 * Activity stays up until [close]. The wearer sees nothing — the HUD overlay
 * (SYSTEM_ALERT_WINDOW) renders the Listening UI on top; this Activity is fully
 * transparent and only exists to own the foreground for AppOps.
 */
object MicGate {

    @Volatile private var activity: Activity? = null
    @Volatile private var resumed: Boolean = false

    /** Bring the transparent gate to the foreground (idempotent). Call BEFORE
     *  AudioRecord.startRecording() — AppOps evaluates RECORD_AUDIO at start, so
     *  the Activity must already be RESUMED. */
    fun open(ctx: Context) {
        if (activity != null) return
        resumed = false   // launching fresh; flips true on the new gate's onResume
        Timber.i("MicGate · open (foreground gate for RECORD_AUDIO)")
        val intent = Intent(ctx, MicGateActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        }
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Timber.w(t, "MicGate · startActivity failed")
        }
    }

    /** Finish the gate Activity once listening ends. */
    fun close() {
        Timber.i("MicGate · close")
        activity?.finish()
        activity = null
        resumed = false
    }

    /**
     * Suspend until the gate Activity is actually RESUMED (AppOps RECORD_AUDIO is
     * evaluated at startRecording, so the mic must wait for this), capped at
     * [timeoutMs] so a missing/slow gate never hangs the turn — at the ceiling it
     * just proceeds like the old blind delay. Returns whether RESUMED was reached.
     *
     * Replaces a fixed 450ms `delay` (Zack 2026-06-02 latency pass): the gate
     * typically resumes in ~200ms, so awaiting the real signal shaves ~250ms off
     * the start of every listen.
     */
    suspend fun awaitResumed(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (!resumed && System.currentTimeMillis() - start < timeoutMs) {
            delay(15L)
        }
        return resumed
    }

    internal fun onCreated(a: Activity) { activity = a }
    internal fun onResumed(a: Activity) { activity = a; resumed = true }
    internal fun onPaused(a: Activity) { if (activity === a) resumed = false }
    internal fun onDestroyed(a: Activity) { if (activity === a) { activity = null; resumed = false } }
}

/** The one-shot transparent gate Activity. Declared in main/AndroidManifest.xml
 *  with `Theme.Translucent.NoTitleBar`. It does nothing but be RESUMED. */
class MicGateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MicGateActivity · onCreate (foreground for RECORD_AUDIO)")
        MicGate.onCreated(this)
    }

    override fun onResume() {
        super.onResume()
        MicGate.onResumed(this)
    }

    override fun onPause() {
        MicGate.onPaused(this)
        super.onPause()
    }

    override fun onDestroy() {
        MicGate.onDestroyed(this)
        super.onDestroy()
    }
}
