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
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.hud.HeadlessHudSurface
import com.constellation.glass.hud.HudRenderer
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.state.AppState
import com.constellation.glass.state.StateMachine
import com.constellation.glass.wss.WssClient
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.utils.CxrDefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Single ForegroundService that owns the entire Glass-side runtime.
 *
 * Lifecycle:
 *   onCreate        — channel + scope + state flow
 *   onStartCommand  — startForeground; connect WSS; bind CXRLink; wire state machine
 *   onDestroy       — cancel scope; close cxrLink; close WSS
 *
 * Token: read from [TokenStore] on first start. If missing, we surface a
 * notification asking the user to open MainActivity to authorize, and stay
 * idle (no HUD work) until the token shows up.
 */
class ConstellationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AppState.Idle)
    val state: StateFlow<AppState> = _state

    private lateinit var wss: WssClient
    private var cxrLink: CXRLink? = null
    private var hud: HudSurface? = null
    private var stateMachine: StateMachine? = null
    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("ConstellationService · onCreate")
        ensureNotificationChannel()
        wss = WssClient(
            url = BuildConfig.WSS_URL,
            scope = scope,
            cookieProvider = { CookieStore.read(this)?.toHeader() },
            onUnauthorized = {
                // Cookie no longer valid (expired or revoked). Clear it,
                // surface a notification, and stop reconnecting until
                // MainActivity re-runs the password login.
                Timber.w("ConstellationService · WSS 401 — clearing cookie, prompting user")
                CookieStore.clear(this)
                updateNotification("Cortex session expired — open the app to re-login")
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_text_idle)))

        // Edge cookie is required for WSS in BOTH paths. Without it we'll
        // get HTTP 403 from the cookie middleware. Pause + prompt for login.
        val cookie = CookieStore.read(this)
        if (cookie == null) {
            Timber.w("ConstellationService · no edge cookie — pausing until login")
            updateNotification("Open Constellation to enter your Cortex password")
            return START_STICKY
        }

        // ── Headless dev mode: skip Rokid entirely, run WSS + state machine
        //    with a logging stub HUD. Lets us validate the network path on
        //    any Android device.
        if (BuildConfig.DEV_HEADLESS) {
            if (hud == null) {
                Timber.i("ConstellationService · DEV_HEADLESS — using HeadlessHudSurface")
                hud = HeadlessHudSurface()
                stateMachine = StateMachine(
                    scope = scope,
                    wss = wss,
                    stateFlow = _state,
                    hudRenderer = hud!!,
                )
                updateNotification("DEV headless — connected to Cortex over WSS")
            }
            if (collectJob == null) {
                collectJob = scope.launch {
                    wss.connect()
                    stateMachine?.bind()
                }
            }
            return START_STICKY
        }

        // ── Production path: Rokid CXR-L + token required.
        val token = TokenStore.read(this)
        if (token == null) {
            Timber.w("ConstellationService · no auth token — pausing")
            updateNotification("Authorize Constellation in the app to enable HUD")
            return START_STICKY
        }
        if (cxrLink == null) {
            initCxrLink(token)
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
        scope.cancel()
        wss.disconnect()
        cxrLink?.let {
            try { it.customViewClose() } catch (_: Throwable) {}
        }
        cxrLink = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── CXR-L wiring ────────────────────────────────────────────────────

    private fun initCxrLink(token: String) {
        Timber.i("ConstellationService · initCxrLink")
        val link = CXRLink(applicationContext)
        // Session type MUST be set before connect(), per the sample.
        try {
            link.configCXRSession(
                CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMVIEW)
            )
        } catch (t: Throwable) {
            Timber.w(t, "ConstellationService · configCXRSession threw")
        }
        // Connection state callback.
        link.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(connected: Boolean) {
                Timber.i("ConstellationService · onCXRLConnected: $connected")
                if (!connected) {
                    // Service is gone (system update? Sprite restart?) — drop HUD.
                    transitionTo(AppState.Idle)
                }
            }
            override fun onGlassBtConnected(btConnected: Boolean) {
                Timber.i("ConstellationService · onGlassBtConnected: $btConnected")
            }
            override fun onGlassAiAssistStart() {
                // Sprite assistant ("Hi Rokid") preempting us — transition to IDLE so
                // we don't fight for the panel.
                Timber.i("ConstellationService · Sprite started — yielding to IDLE")
                transitionTo(AppState.Idle)
            }
            override fun onGlassAiAssistStop() {
                Timber.i("ConstellationService · Sprite stopped")
            }
        })

        cxrLink = link
        hud = HudRenderer(
            cxrLink = link,
            onSystemClosed = { transitionTo(AppState.Idle) },
        )
        stateMachine = StateMachine(
            scope = scope,
            wss = wss,
            stateFlow = _state,
            hudRenderer = hud!!,
        )

        // Now connect.
        try {
            val ok = link.connect(token)
            Timber.i("ConstellationService · cxrLink.connect → $ok")
            if (!ok) {
                Timber.w("ConstellationService · cxrLink.connect returned false — token may be invalid")
                TokenStore.clear(this)
                updateNotification("Auth invalid — reopen the app to re-authorize")
            }
        } catch (t: Throwable) {
            Timber.w(t, "ConstellationService · cxrLink.connect threw")
        }
    }

    /** Forced state transition (e.g. from system Sprite preempt). */
    private fun transitionTo(next: AppState) {
        val prev = _state.value
        if (prev == next) return
        _state.value = next
        hud?.transition(prev, next)
    }

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
    }
}
