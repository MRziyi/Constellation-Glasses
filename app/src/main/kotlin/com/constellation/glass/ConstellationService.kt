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
import com.constellation.glass.app.EndpointStore
import com.constellation.glass.audio.AudioPipeline
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.input.InputHandler
import com.constellation.glass.state.AppState
import com.constellation.glass.state.StateMachine
import com.constellation.glass.wss.WssClient
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_text_idle)))

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
            )
            adapter.installInputListener(this)
            updateNotification("Constellation · running on ${BuildConfig.PLATFORM}")
        }

        if (collectJob == null) {
            collectJob = scope.launch {
                wss.connect()
                stateMachine?.bind()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("ConstellationService · onDestroy")
        audio?.stop()
        adapter.uninstallInputListener()
        hud?.destroy()
        adapter.destroy()
        scope.cancel()
        wss.disconnect()
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
    }
}
