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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.constellation.glass.hud.HudSurface
import com.constellation.glass.hud.ScrollWindow
import com.constellation.glass.hud.StyledRunsRenderer
import com.constellation.glass.hud.composables.AppStateHud
import com.constellation.glass.state.AppState
import org.json.JSONArray
import timber.log.Timber

/**
 * phoneDebug HUD surface — a **Rokid Glasses simulator** that runs on a
 * regular Android phone via SYSTEM_ALERT_WINDOW.
 *
 * P1.6: rewritten to host the same Compose [AppStateHud] composable that the
 * glass flavor uses, inside a 4:3 box at the panel's native aspect ratio.
 * This means visual / per-run-styling iteration works on the OnePlus 9 (or
 * any phone) without needing the actual Rokid Glasses dev cable.
 *
 * The simulator is **proportional, not pixel-accurate**:
 *   - Box uses aspectRatio(480/640), filling phone width
 *   - Internal layout (HudTheme.sidePadding etc.) uses dp — actual glass has
 *     different pixel density; P1.5 real-device test will calibrate
 *   - "GLASS SIM" label + state indicator above the box make it obvious this
 *     is a debug surface, not the eyewear UI
 *
 * If overlay permission is denied, [destroy] is a no-op and [LoggingHudSurface]
 * is used by the adapter as a fallback. See [PhoneDebugPlatformAdapter].
 */
class PhoneDebugHudSurface(private val ctx: Context) : HudSurface {

    private val wm: WindowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    /** Minimal lifecycle/savedstate/viewmodel owner for the ComposeView inside
     *  the overlay. Set up in init, torn down in [destroy]. */
    private val hostOwner = OverlayHostOwner()

    /** Active card body viewport. Null when no card is up. */
    private var scrollWindow: ScrollWindow? = null

    /** Chars-per-line for the simulator. Matches glass GlassHudSurface so behavior is parallel. */
    private val cardBodyWrapChars = 28
    private val cardBodyWindowLines = 6

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
        Timber.i("PhoneDebugHudSurface · $prev → $next")
        PhoneDebugHudState.update { copy(appState = next) }
    }

    override fun updateThinking(icon: String, detailRuns: JSONArray?, metaRuns: JSONArray?) {
        PhoneDebugHudState.update {
            copy(icon = icon, detailRuns = detailRuns, metaRuns = metaRuns)
        }
    }

    override fun updateListening(elapsedSec: Int, amplitude: Float, partialRuns: JSONArray?) {
        PhoneDebugHudState.update {
            copy(
                listeningElapsedSec = elapsedSec,
                listeningAmplitude = amplitude,
                listeningPartialRuns = partialRuns ?: listeningPartialRuns,
            )
        }
    }

    override fun showCard(
        cardId: String,
        titleRuns: JSONArray?,
        bodyRuns: JSONArray?,
        options: List<String>,
    ) {
        val (flatBody, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(bodyRuns))
        val wrapped = ScrollWindow.wrap(flatBody, maxChars = cardBodyWrapChars)
        val window = ScrollWindow(wrapped, windowSize = cardBodyWindowLines)
        scrollWindow = window
        PhoneDebugHudState.update {
            copy(
                cardId = cardId,
                cardTitleRuns = titleRuns,
                cardBodyText = window.windowText(),
                cardScrollPos = if (window.totalWindows() > 1) window.position() else 0,
                cardScrollTotal = if (window.totalWindows() > 1) window.totalWindows() else 0,
                cardOptions = options,
            )
        }
    }

    override fun showInsight(titleRuns: JSONArray?, bodyRuns: JSONArray?, ttlSec: Int) {
        PhoneDebugHudState.update {
            copy(
                insightTitleRuns = titleRuns,
                insightBodyRuns = bodyRuns,
                insightTtlSec = ttlSec,
            )
        }
    }

    override fun scrollCardUp(): Boolean {
        val w = scrollWindow ?: return false
        if (!w.scrollUp()) return false
        PhoneDebugHudState.update {
            copy(cardBodyText = w.windowText(), cardScrollPos = w.position())
        }
        return true
    }

    override fun scrollCardDown(): Boolean {
        val w = scrollWindow ?: return false
        if (!w.scrollDown()) return false
        PhoneDebugHudState.update {
            copy(cardBodyText = w.windowText(), cardScrollPos = w.position())
        }
        return true
    }

    override fun destroy() {
        overlay?.let {
            try { wm.removeView(it) } catch (_: Throwable) {}
        }
        overlay = null
        hostOwner.destroy()
    }

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
            y = dp(40)
        }
        try {
            wm.addView(view, params)
            overlay = view
            Timber.i("PhoneDebugHudSurface · overlay attached (glass-sim mode)")
        } catch (t: Throwable) {
            Timber.w(t, "PhoneDebugHudSurface · addView failed")
        }
    }

    /**
     * Container view: native LinearLayout for the outer chrome (title label,
     * background, padding) + a ComposeView hosting the shared [AppStateHud]
     * inside a 480:640 box. Why mix Views with Compose? ComposeView needs a
     * Lifecycle/SavedState owner to attach correctly from a WindowManager
     * overlay — wrapping it in a plain LinearLayout keeps the lifecycle wiring
     * isolated and lets the outer chrome stay simple.
     */
    private fun buildOverlayView(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E6000000"))
                setStroke(dp(1), Color.parseColor("#5EE08C"))
            }
            // ViewTree owners must live on the ROOT view (the rootView Compose
            // walks up to find them) — not on the ComposeView itself.
            // WindowManager-attached views have no Activity/Fragment, so we
            // attach our own OverlayHostOwner here.
            setViewTreeLifecycleOwner(hostOwner)
            setViewTreeViewModelStoreOwner(hostOwner)
            setViewTreeSavedStateRegistryOwner(hostOwner)
        }
        // "GLASS SIM" header strip — clarifies this is debug not real
        root.addView(TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.parseColor("#5EE08C"))
            text = "▣  GLASS SIM  ·  Rokid Glasses 480×640 (proportional)"
        })

        // The Compose simulator box. ViewTree owners are wired on `root` above
        // — Compose walks up to rootView to find them.
        root.addView(ComposeView(ctx).apply {
            setContent {
                val snap by PhoneDebugHudState.snapshot.collectAsState()
                GlassSimulatorBox(
                    state = snap.appState.name,
                    composeContent = { AppStateHud(snap) },
                )
            }
        })
        return root
    }

    private fun post(block: () -> Unit) {
        if (overlay == null) return
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else main.post(block)
    }

    private fun dp(v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()
}

/**
 * The bordered 4:3 simulator box + state-name strip.
 * Lives at file scope (not inside the surface class) so Compose Preview can
 * exercise it without instantiating WindowManager.
 */
@Composable
private fun GlassSimulatorBox(
    state: String,
    composeContent: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        // 4:3 box — the actual Rokid Glasses panel aspect
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(480f / 640f)
                .background(ComposeColor.Black)
                .border(width = 1.dp, color = ComposeColor(0x405EE08C)),
        ) {
            composeContent()
        }
        // State label below
        BasicText(
            text = "[$state]",
            style = TextStyle(fontSize = 10.sp, color = ComposeColor(0xFF5EE08C)),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
