package com.constellation.glass.phonedebug

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.constellation.glass.hud.HudLayouts
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.state.AppState
import org.json.JSONArray
import timber.log.Timber

/**
 * phoneDebug-only HUD surface. Floating overlay drawn via
 * SYSTEM_ALERT_WINDOW so we can validate the state machine + WSS frames on a
 * regular Android phone with no Rokid hardware.
 *
 * Visual style mirrors the right-eye HUD (monochrome green on near-black) but
 * is intentionally positioned as a corner panel so the rest of the phone UI
 * stays usable.
 *
 * If overlay permission is denied, [destroy] is a no-op and [LoggingHudSurface]
 * is used by the adapter as a fallback. See [PhoneDebugPlatformAdapter].
 */
class PhoneDebugHudSurface(private val ctx: Context) : HudSurface {

    private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    private lateinit var stateLabel: TextView
    private lateinit var iconText: TextView
    private lateinit var waveText: TextView
    private lateinit var primaryText: TextView
    private lateinit var partialText: TextView
    private lateinit var footerText: TextView

    private val canDraw: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(ctx)
        } else true

    init {
        if (!canDraw) {
            Timber.w("PhoneDebugHudSurface · SYSTEM_ALERT_WINDOW not granted — overlay disabled")
        } else {
            main.post { addOverlay() }
        }
    }

    // ── HudSurface ─────────────────────────────────────────────────────────

    override fun transition(prev: AppState, next: AppState) {
        post {
            stateLabel.text = "[debug] $next"
            stateLabel.setTextColor(stateColor(next))
            when (next) {
                AppState.Idle -> {
                    iconText.text = "✦ idle"
                    waveText.text = ""
                    primaryText.text = ""
                    partialText.text = ""
                    footerText.text = "wait for trigger"
                }
                AppState.Listening -> {
                    iconText.text = "🎤 listening…"
                    waveText.text = HudLayouts.gwave(0f)
                    primaryText.text = ""
                    partialText.text = ""
                    footerText.text = "speak — partials will appear"
                }
                AppState.Thinking -> {
                    iconText.text = "⌛ thinking"
                    waveText.text = ""
                    primaryText.text = ""
                    partialText.text = ""
                    footerText.text = ""
                }
                AppState.Card -> {
                    iconText.text = "▣ card"
                    waveText.text = ""
                    partialText.text = ""
                    footerText.text = "好 approve · 改 modify · 停 kill"
                }
                AppState.Insight -> {
                    iconText.text = "✦ insight"
                    waveText.text = ""
                    partialText.text = ""
                    footerText.text = "看一下 engage"
                }
                AppState.Offline -> {
                    iconText.text = "● offline"
                    waveText.text = ""
                    partialText.text = ""
                    footerText.text = "reconnecting…"
                }
            }
        }
    }

    override fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?) {
        val detail = flatten(detailRuns).ifEmpty { "thinking…" }
        val meta = flatten(metaRuns)
        post {
            iconText.text = "${icon.ifEmpty { "⌛" }} $detail"
            primaryText.text = ""
            partialText.text = ""
            footerText.text = meta
        }
    }

    override fun updateListening(elapsedSec: Int, amplitude: Float, partialRuns: JSONArray?) {
        val partial = flatten(partialRuns)
        post {
            waveText.text = HudLayouts.gwave(amplitude)
            if (partial.isNotEmpty()) partialText.text = partial
            footerText.text = "say \"完了\" to send · amp=${(amplitude * 100).toInt()}%"
        }
    }

    override fun showCard(
        cardId: String, titleRuns: JSONArray?, bodyRuns: JSONArray?, options: List<String>
    ) {
        post {
            iconText.text = "▣ ${flatten(titleRuns).ifEmpty { "Card" }}"
            primaryText.text = flatten(bodyRuns)
            partialText.text = ""
            footerText.text = options.joinToString(" · ") {
                when (it.lowercase()) {
                    "approve" -> "好 approve"
                    "modify" -> "改 modify"
                    "kill" -> "停 kill"
                    else -> it
                }
            }
        }
    }

    override fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int) {
        post {
            iconText.text = "✦ ${flatten(titleRuns).ifEmpty { "Insight" }}"
            primaryText.text = flatten(bodyRuns)
            partialText.text = ""
            footerText.text = "auto-close ${ttlSec}s"
        }
    }

    override fun scrollCardUp(): Boolean = false
    override fun scrollCardDown(): Boolean = false

    // ── overlay setup ──────────────────────────────────────────────────────

    private fun addOverlay() {
        val view = buildOverlayView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }
        try {
            wm.addView(view, params)
            overlay = view
            Timber.i("PhoneDebugHudSurface · overlay attached")
        } catch (t: Throwable) {
            Timber.w(t, "PhoneDebugHudSurface · addView failed")
        }
    }

    private fun buildOverlayView(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#CC050A06"))
                setStroke(dp(1), Color.parseColor("#205EE08C"))
            }
        }
        stateLabel = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#5EE08C"))
            text = "[debug] Idle"
        }
        iconText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.parseColor("#00FF00"))
            setPadding(0, dp(4), 0, 0)
            text = "✦ idle"
        }
        waveText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTextColor(Color.parseColor("#00FF66"))
            // Mono-ish: helps the block-glyph wave line up.
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
            text = ""
        }
        primaryText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#00CC00"))
            setPadding(0, dp(8), 0, 0)
            text = ""
        }
        partialText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#88FFAA"))
            setPadding(0, dp(8), 0, 0)
            text = ""
        }
        footerText = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Color.parseColor("#5EE08C"))
            setPadding(0, dp(10), 0, 0)
            text = "wait for trigger"
        }
        root.addView(stateLabel)
        root.addView(iconText)
        root.addView(waveText)
        root.addView(primaryText)
        root.addView(partialText)
        root.addView(footerText)
        return root
    }

    /** Call when the service is going away, to detach the overlay cleanly. */
    override fun destroy() {
        overlay?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        overlay = null
    }

    private fun stateColor(s: AppState): Int = when (s) {
        AppState.Idle -> Color.parseColor("#5EE08C")
        AppState.Listening -> Color.parseColor("#FFD24A")
        AppState.Thinking -> Color.parseColor("#5EE08C")
        AppState.Card -> Color.parseColor("#A0FFB7")
        AppState.Insight -> Color.parseColor("#88E0FF")
        AppState.Offline -> Color.parseColor("#FF7C7C")
    }

    private fun post(block: () -> Unit) {
        if (overlay == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post(block)
    }

    private fun flatten(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            sb.append(o.optString("text", ""))
        }
        return sb.toString()
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}
