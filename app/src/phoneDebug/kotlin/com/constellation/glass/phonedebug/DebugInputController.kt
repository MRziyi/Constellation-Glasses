package com.constellation.glass.phonedebug

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import com.constellation.glass.input.InputHandler
import timber.log.Timber

/**
 * phoneDebug input source. Posts a persistent notification with action
 * buttons that simulate the glass key events. Tap an action → broadcast →
 * receiver → InputHandler.
 *
 * Why: on a phone we don't have the right-temple button or the touchpad,
 * but we want to drive the same state machine end-to-end. The notification
 * is always visible while the service runs, so the user can trigger any
 * gesture without unlocking specialized UI.
 */
class DebugInputController(private val ctx: Context) {

    companion object {
        private const val CHANNEL_ID = "constellation_debug_input"
        private const val NOTIF_ID = 1002

        private const val ACT_CLICK = "com.constellation.glass.phonedebug.CLICK"
        private const val ACT_LONG = "com.constellation.glass.phonedebug.LONG_PRESS"
        private const val ACT_DBL = "com.constellation.glass.phonedebug.DOUBLE_CLICK"
        private const val ACT_TF_FWD = "com.constellation.glass.phonedebug.TF_FWD"
        private const val ACT_TF_BACK = "com.constellation.glass.phonedebug.TF_BACK"
        private const val ACT_TF_TAP = "com.constellation.glass.phonedebug.TF_TAP"
        private const val ACT_TF_DBL = "com.constellation.glass.phonedebug.TF_DBL"
        private const val ACT_SETTINGS = "com.constellation.glass.phonedebug.SETTINGS"
    }

    private var receiver: BroadcastReceiver? = null
    private var handler: InputHandler? = null

    fun install(handler: InputHandler) {
        this.handler = handler
        ensureChannel()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val h = this@DebugInputController.handler ?: return
                Timber.v("DebugInput · ${intent?.action}")
                when (intent?.action) {
                    ACT_CLICK -> h.onPrimaryClick()
                    ACT_LONG -> h.onPrimaryLongPress()
                    ACT_DBL -> h.onPrimaryDoubleClick()
                    ACT_TF_FWD -> h.onTwoFingerSwipeForward()
                    ACT_TF_BACK -> h.onTwoFingerSwipeBack()
                    ACT_TF_TAP -> h.onTwoFingerSingleTap()
                    ACT_TF_DBL -> h.onTwoFingerDoubleTap()
                    ACT_SETTINGS -> h.onSettingsKey()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACT_CLICK); addAction(ACT_LONG); addAction(ACT_DBL)
            addAction(ACT_TF_FWD); addAction(ACT_TF_BACK)
            addAction(ACT_TF_TAP); addAction(ACT_TF_DBL); addAction(ACT_SETTINGS)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        postNotification()
    }

    fun uninstall() {
        receiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Throwable) {}
        }
        receiver = null
        handler = null
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)
    }

    private fun ensureChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = android.app.NotificationChannel(
            CHANNEL_ID, "Constellation debug input",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Phone-debug input simulator (Click / Long / etc.)"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun postNotification() {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun pi(action: String) =
            PendingIntent.getBroadcast(ctx, action.hashCode(), Intent(action).setPackage(ctx.packageName), flags)

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(com.constellation.glass.R.drawable.ic_notification)
            .setContentTitle("Constellation · debug input")
            .setContentText("Click / Long / Double / 2F-swipe / Settings")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .addAction(0, "Click", pi(ACT_CLICK))
            .addAction(0, "Long", pi(ACT_LONG))
            .addAction(0, "Dbl", pi(ACT_DBL))
            .addAction(0, "← back", pi(ACT_TF_BACK))
            .addAction(0, "fwd →", pi(ACT_TF_FWD))
            .build()
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, n)
    }
}
