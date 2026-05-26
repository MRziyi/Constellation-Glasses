package com.constellation.glass

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.constellation.glass.state.AppState
import com.constellation.glass.state.StateMachine
import com.constellation.glass.wss.WssClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The single ForegroundService that holds everything: the CXR-L connection,
 * the WSS to Cortex, the state machine, the InstructSdk hookup. No visible
 * Activity. Always-on, survives Doze.
 *
 * Lifecycle:
 *   onCreate    → set up notification channel, scope, state machine
 *   onStartCmd  → start FGS, connect WSS, become reachable
 *   onDestroy   → tear down WSS + scope (rare — service is meant to live as
 *                 long as the device is on)
 */
class ConstellationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val _state = MutableStateFlow(AppState.Idle)
    val state: StateFlow<AppState> = _state

    private lateinit var wss: WssClient
    private lateinit var stateMachine: StateMachine

    private var collectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Timber.i("ConstellationService · onCreate")
        ensureNotificationChannel()
        wss = WssClient(BuildConfig.WSS_URL, scope)
        stateMachine = StateMachine(
            scope = scope,
            wss = wss,
            stateFlow = _state,
            // hud renderer / instruct host / halo bridge wired in 3b.2 / 3b.3
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_text_idle)))
        if (collectJob == null) {
            collectJob = scope.launch {
                wss.connect()
                stateMachine.bind()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Timber.i("ConstellationService · onDestroy")
        scope.cancel()
        wss.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── notification ───────────────────────────────────────────────────

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

    /** Update the persistent notification text — called from state transitions. */
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
