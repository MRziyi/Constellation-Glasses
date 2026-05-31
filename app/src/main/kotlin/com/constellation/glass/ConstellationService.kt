package com.constellation.glass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Base64
import com.constellation.glass.app.EndpointStore
import com.constellation.glass.audio.AudioPipeline
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.camera.CameraGate
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.input.InputHandler
import com.constellation.glass.halo.HaloOverlay
import com.constellation.glass.state.AppState
import com.constellation.glass.state.StateMachine
import com.constellation.glass.wss.GlassEvent
import com.constellation.glass.wss.WssClient
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Single ForegroundService that owns the runtime. v2.1 (bare-metal):
 *
 *   onCreate        — notification channel + scope + state flow + adapter wiring
 *   onStartCommand  — startForeground; if cookie present, connect WSS + bind
 *                     state machine + install input listener; otherwise idle
 *                     and prompt for login
 *   onDestroy       — cancel scope; teardown adapter; close WSS
 *
 * Platform specifics (HUD, audio capture, input source) live behind
 * [HudPlatformAdapter], resolved per productFlavor (`glass` / `phoneDebug`).
 * No CXR-L imports; no Rokid token; no Sprite IPC.
 *
 * Input routing: this Service is the [InputHandler] — its methods drive
 * [stateMachine.handleInput]. Platform-installed listeners (system broadcast
 * receiver on glass; notification-action receiver on phoneDebug) call us.
 */
class ConstellationService : Service(), InputHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AppState.Idle)
    val state: StateFlow<AppState> = _state

    private lateinit var wss: WssClient
    private lateinit var adapter: HudPlatformAdapter
    private var hud: HudSurface? = null
    private var audio: AudioPipeline? = null
    private var stateMachine: StateMachine? = null
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.i("ConstellationService · onCreate")
        ensureNotificationChannel()
        adapter = HudPlatformAdapter.create(applicationContext)
        // Read endpoint from DataStore (P-app.A D5: runtime-editable). Blocking
        // first read on onCreate — DataStore reads are µs-scale local file ops.
        // BuildConfig.WSS_URL is the fallback when nothing's been saved yet.
        val endpoint = runBlocking { EndpointStore.flow(applicationContext).first() }
        Timber.i("ConstellationService · endpoint=$endpoint")
        wss = WssClient(
            url = endpoint,
            scope = scope,
            cookieProvider = { CookieStore.read(this)?.toHeader() },
            onUnauthorized = {
                Timber.w("ConstellationService · WSS unauthorized — clearing cookie")
                CookieStore.clear(this)
                updateNotification("Cortex session expired — open the app to re-login")
            },
        )
        // Pre-warm CameraX. The first ProcessCameraProvider.getInstance() pays a
        // ~5.8s cold init on this device (measured 2026-05-30); kicking it off in
        // the background at service start means the first photo capture hits a
        // warm provider (~1.6s) instead of paying that init on the user-facing
        // "capturing scene…" path. getInstance() is a process-scoped singleton.
        try {
            androidx.camera.lifecycle.ProcessCameraProvider.getInstance(applicationContext)
            Timber.i("ConstellationService · CameraX pre-warm kicked off")
        } catch (t: Throwable) {
            Timber.w(t, "ConstellationService · CameraX pre-warm failed")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Pass FGS types explicitly at startForeground (in addition to the
        // manifest declaration). The manifest alone is sufficient on most
        // Android 12-13 targetSdk=32 devices, but YodaOS-Sprite on Rokid
        // Glasses blocks CameraX with ERROR_CAMERA_DISABLED unless the
        // service is foreground'd with the explicit `camera` type included.
        // Discovered 2026-05-28 during real-device vision shortcut E2E.
        // ServiceCompat picks the right startForeground overload per API level.
        //
        // 2026-05-29 P1.8 finding: include FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // for the persistent WSS keepalive. Without it, Android demotes us
        // to PERCEPTIBLE_APP_ADJ (200) after HOMing because mic/camera aren't
        // actively recording at idle. With dataSync the WSS keepalive itself
        // qualifies as foreground work, keeping us at FOREGROUND_SERVICE_ADJ.
        // Real-device dumpsys before fix: isForeground=false; after: should
        // be isForeground=true even when MainActivity is backgrounded.
        val fgsTypes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        else 0
        androidx.core.app.ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(getString(R.string.notif_text_idle)),
            fgsTypes,
        )

        val cookie = CookieStore.read(this)
        if (cookie == null) {
            Timber.w("ConstellationService · no edge cookie — pausing until login")
            updateNotification("Open Constellation to enter your Cortex password")
            return START_STICKY
        }

        if (hud == null) {
            hud = adapter.createHudSurface()
            val capture = adapter.createAudioCapture()
            audio = AudioPipeline(capture, wss, scope)
            stateMachine = StateMachine(
                scope = scope,
                wss = wss,
                stateFlow = _state,
                hudRenderer = hud!!,
                audio = audio,
                onImageRequested = { reqId, hint -> handleImageRequest(reqId, hint) },
                onShortcutConfig = { slot, prompt, sendPhoto, label ->
                    ShortcutSlots.update(this@ConstellationService, slot, prompt, sendPhoto, label)
                },
            )
            adapter.installInputListener(this)
            updateNotification("Constellation · running on ${BuildConfig.PLATFORM}")
        }

        if (collectJob == null) {
            collectJob = scope.launch {
                wss.connect()
                stateMachine?.bind()
            }
            // Ring takeover (RESP-halo-ring-overlay-protocol-v1): while the HUD
            // is visible (any non-Idle/Offline state) we claim the ring as an
            // exclusive overlay — every gesture forwards to us, the launcher
            // gets nothing. ACTIVATE must be re-sent every ~25s as keepalive
            // (ring auto-releases after 60s of silence). DEACTIVATE on Idle.
            scope.launch {
                var keepaliveJob: kotlinx.coroutines.Job? = null
                var active = false
                _state.collect { st ->
                    val hudActive = st != AppState.Idle && st != AppState.Offline
                    if (hudActive && !active) {
                        active = true
                        HaloOverlay.activate(this@ConstellationService)
                        keepaliveJob = scope.launch {
                            while (true) {
                                kotlinx.coroutines.delay(HaloOverlay.KEEPALIVE_MS)
                                HaloOverlay.activate(this@ConstellationService)
                            }
                        }
                    } else if (!hudActive && active) {
                        active = false
                        keepaliveJob?.cancel(); keepaliveJob = null
                        HaloOverlay.deactivate(this@ConstellationService)
                    }
                }
            }
            // Mic foreground gate: while LISTENING, keep a transparent Activity
            // RESUMED so AppOps RECORD_AUDIO:foreground is satisfied even when
            // the app UI is HOMEd (YodaOS denies a backgrounded mic → silence).
            // See MicGate / C-51 (camera) — same vendor gate.
            scope.launch {
                var micGated = false
                _state.collect { st ->
                    val listening = st == AppState.Listening
                    if (listening && !micGated) {
                        micGated = true
                        com.constellation.glass.audio.MicGate.open(this@ConstellationService)
                    } else if (!listening && micGated) {
                        micGated = false
                        com.constellation.glass.audio.MicGate.close()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("ConstellationService · onDestroy")
        // Release the ring overlay so it isn't left claimed if the Service dies
        // while a card was up (the ring's 60s keepalive timeout would also
        // recover, but release promptly). Idempotent no-op if not active.
        HaloOverlay.deactivate(this)
        audio?.stop()
        adapter.uninstallInputListener()
        hud?.destroy()
        adapter.destroy()
        scope.cancel()
        wss.disconnect()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── InputHandler (routes to StateMachine) ──────────────────────────────

    override fun onPrimaryClick() = stateMachine?.handlePrimaryClick() ?: Unit
    override fun onPrimaryLongPress() = stateMachine?.handlePrimaryLongPress() ?: Unit
    override fun onPrimaryDoubleClick() = stateMachine?.handlePrimaryDoubleClick() ?: Unit
    override fun onTwoFingerSingleTap() = stateMachine?.handleTwoFingerTap() ?: Unit
    override fun onTwoFingerDoubleTap() = stateMachine?.handleTwoFingerDoubleTap() ?: Unit
    override fun onTwoFingerSwipeForward() = stateMachine?.handleTwoFingerSwipeForward() ?: Unit
    override fun onTwoFingerSwipeBack() = stateMachine?.handleTwoFingerSwipeBack() ?: Unit
    override fun onSettingsKey() = Unit  // future: open settings activity

    // ── notification ────────────────────────────────────────────────────

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                getString(R.string.notif_channel_id),
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Constellation glass-side service"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(content: String): Notification =
        NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)
            .build()

    fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIF_ID = 1001

        /**
         * Live reference to the currently running Service. Volatile so writes
         * from onCreate/onDestroy publish to readers on other threads (e.g.
         * the [com.constellation.glass.halo.HaloTriggerReceiver] running on
         * the binder thread).
         *
         * Null when the Service isn't alive — call sites must defend against
         * that (and either start the service or treat the action as a no-op).
         */
        @Volatile
        private var instance: ConstellationService? = null

        fun start(ctx: Context) {
            val intent = Intent(ctx, ConstellationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        /**
         * Bounce the service so it re-reads the endpoint from [EndpointStore].
         * Called by the in-app Edit Endpoint screen after saving a new URL.
         *
         * Implementation: stopService → start. The brief gap between teardown
         * and recreate (a few hundred ms) is acceptable because the only
         * user-visible behavior change is "WSS reconnects to new endpoint" —
         * the HUD will briefly show Offline → Idle, which is correct UX
         * feedback for "I just changed where the brain lives".
         */
        fun reconfigure(ctx: Context) {
            Timber.i("ConstellationService · reconfigure requested")
            ctx.stopService(Intent(ctx, ConstellationService::class.java))
            start(ctx)
        }

        /**
         * External trigger for the "primary click" input — equivalent to the
         * user pressing CLICK on the eyewear temple button. Used by:
         *   - [com.constellation.glass.halo.HaloTriggerReceiver] when Halo
         *     Ring fires the `voice_invoke` core action.
         *   - Future debug surfaces / accessibility paths.
         *
         * If the service isn't running yet (rare — usually means user hasn't
         * logged in), we [start] it; the click is dropped this round but the
         * service will be alive for the next.
         */
        fun startListening(ctx: Context) {
            val s = instance
            if (s == null) {
                Timber.i("ConstellationService.startListening · service not running; starting")
                start(ctx)
                return
            }
            s.onPrimaryClick()
        }

        /**
         * Fire a shortcut from outside the Service (typically [com.constellation.glass.halo.HaloTriggerReceiver]
         * after a Halo Ring `shortcut_*` trigger). The Service runs the
         * camera + HTTP work on its own [CoroutineScope] so we're not
         * subject to a BroadcastReceiver's ~10s budget.
         *
         * If the Service isn't running, we start it first and re-dispatch
         * on the next cycle (the shortcut fires from a cold start in ~3-4s).
         */
        fun fireShortcut(ctx: Context, shortcutId: String) {
            val s = instance
            if (s == null) {
                Timber.i("ConstellationService.fireShortcut · service not running; starting + deferring '$shortcutId'")
                start(ctx)
                // Naive retry — give the service a moment to come up.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    instance?.fireShortcutInternal(shortcutId)
                }, 2_000L)
                return
            }
            s.fireShortcutInternal(shortcutId)
        }

        /**
         * Ring overlay gestures (RESP-halo-ring-overlay-protocol-v1). While our
         * overlay is active the ring forwards raw gesture NAMES via
         * OVERLAY_GESTURE; [com.constellation.glass.halo.HaloOverlayGestureReceiver]
         * routes them here. We own the mapping — each maps onto the existing
         * state-aware InputHandler so the ring drives exactly what the temple
         * button used to. No-op if the Service isn't running (no HUD to act on).
         *
         * Mapping: TAP=approve/engage/end-listening · DOUBLE_TAP=dismiss/kill ·
         * LONG_PRESS=modify · SWIPE_UP/DOWN=scroll.
         */
        fun hudGesture(ctx: Context, gesture: String) {
            val s = instance ?: run {
                Timber.i("ConstellationService.hudGesture · service not running; ignoring '$gesture'")
                return
            }
            when (gesture) {
                HaloOverlay.G_TAP -> s.onPrimaryClick()
                HaloOverlay.G_DOUBLE_TAP -> s.onPrimaryDoubleClick()
                HaloOverlay.G_LONG_PRESS -> s.onPrimaryLongPress()
                HaloOverlay.G_SWIPE_UP -> s.onTwoFingerSwipeBack()
                HaloOverlay.G_SWIPE_DOWN -> s.onTwoFingerSwipeForward()
                else -> Timber.i("ConstellationService.hudGesture · unmapped gesture '$gesture' (ignored)")
            }
        }
    }

    /**
     * R-13 / C-55: Cortex asked for a scene photo (router routed to a
     * vision-aware tool but the user's voice invoke had no image). Capture
     * via [CameraGate] (same path as photo shortcuts), encode b64, send
     * [GlassEvent.ImageAttached] back so Cortex's pending Future resolves.
     *
     * Runs in [scope] so it survives BroadcastReceiver lifetime + camera
     * open latency (typical ~1.5s, well under Cortex's 10s timeout).
     */
    private fun handleImageRequest(reqId: String, hint: String?) {
        scope.launch {
            Timber.i("Service.handleImageRequest · req_id=$reqId hint=$hint · capturing")
            val bytes = try {
                CameraGate.captureViaGate(this@ConstellationService)
            } catch (t: Throwable) {
                Timber.w(t, "Service.handleImageRequest · capture threw")
                null
            }
            val b64 = if (bytes != null && bytes.isNotEmpty()) {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else {
                Timber.w("Service.handleImageRequest · empty/null bytes; sending empty image for graceful fallback")
                ""
            }
            // Send back even on failure (empty string) so Cortex doesn't wait
            // for the full 10s timeout — fail-fast to keep the user moving.
            val ev = GlassEvent.ImageAttached(
                ts = Instant.now().toString(),
                payload = GlassEvent.ImageAttached.Payload(
                    reqId = reqId,
                    image = b64,
                ),
            )
            val ok = wss.sendEvent(ev)
            Timber.i(
                "Service.handleImageRequest · sent image_attached req_id=$reqId · " +
                    "bytes_b64=${b64.length} · wss_ok=$ok"
            )
        }
    }

    /** Runs in [scope] so it's not bound to any receiver lifetime. Reads the
     *  endpoint from DataStore, captures photo if needed, invokes Cortex. */
    private fun fireShortcutInternal(shortcutId: String) {
        // shortcutId is the slot action id "shortcut1".."shortcut3".
        val index = shortcutId.removePrefix("shortcut").toIntOrNull()
        if (index == null || index !in 1..ShortcutSlots.COUNT) {
            Timber.w("Service.fireShortcut · bad slot id '$shortcutId'")
            return
        }
        scope.launch {
            try {
                val endpoint = EndpointStore.flow(this@ConstellationService).first()
                val result = ShortcutFireClient.fireBySlot(
                    this@ConstellationService, index, endpoint,
                )
                when (result) {
                    is ShortcutFireClient.Result.Ok ->
                        Timber.i("Service.fireShortcut · slot $index fired (event=${result.eventId})")
                    is ShortcutFireClient.Result.HttpError ->
                        Timber.w("Service.fireShortcut · slot $index HTTP ${result.code}")
                    is ShortcutFireClient.Result.NetworkError ->
                        Timber.w("Service.fireShortcut · slot $index: ${result.msg}")
                }
            } catch (t: Throwable) {
                Timber.w(t, "Service.fireShortcut · unexpected failure")
            }
        }
    }
}
