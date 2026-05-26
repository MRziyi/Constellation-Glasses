package com.constellation.glass.glass.hud

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.constellation.glass.glass.GlassHudState
import com.constellation.glass.hud.HudLayouts
import com.constellation.glass.hud.StyledRunsRenderer
import com.constellation.glass.state.AppState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONArray
import timber.log.Timber

/**
 * On-eyepiece HUD render Activity (glass flavor).
 *
 *   - Transparent fullscreen window so unlit pixels stay AR-transparent.
 *   - KEEP_SCREEN_ON while alive (we want the panel awake during interactions).
 *   - Observes [GlassHudState.snapshot] and re-renders on every change.
 *   - Plain Android Views (not Compose) — Android Go memory budget on R08
 *     doesn't love Compose tree depth; we'll add Compose later if we need it.
 *
 * Lifecycle:
 *   - First click → Service starts this Activity (FLAG_ACTIVITY_NEW_TASK,
 *     singleTask). onCreate registers state collector.
 *   - System back / double-click → Activity finishes; Service still alive.
 *     Next state transition restarts us.
 */
class GlassHudActivity : ComponentActivity() {

    private lateinit var root: LinearLayout
    private lateinit var iconText: TextView
    private lateinit var detailText: TextView
    private lateinit var metaText: TextView
    private lateinit var waveText: TextView
    private lateinit var partialText: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardBody: TextView
    private lateinit var cardScroll: TextView
    private lateinit var cardFooter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("GlassHudActivity · onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        setContentView(buildView())

        lifecycleScope.launch {
            GlassHudState.snapshot.collectLatest { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Apply latest snapshot in case state mutated while paused.
        render(GlassHudState.snapshot.value)
    }

    override fun onDestroy() {
        Timber.i("GlassHudActivity · onDestroy")
        super.onDestroy()
    }

    // ── render ──────────────────────────────────────────────────────────

    private fun render(snap: GlassHudState.Snapshot) {
        // Layout strategy: hide/show subtrees by AppState. View tree stays
        // mounted (no allocations on transition).
        when (snap.appState) {
            AppState.Idle -> {
                iconText.visibility = View.GONE
                detailText.visibility = View.GONE
                metaText.visibility = View.GONE
                waveText.visibility = View.GONE
                partialText.visibility = View.GONE
                cardTitle.visibility = View.GONE
                cardBody.visibility = View.GONE
                cardScroll.visibility = View.GONE
                cardFooter.visibility = View.GONE
            }
            AppState.Listening -> {
                iconText.visibility = View.VISIBLE
                iconText.text = "🎤 listening…"
                detailText.visibility = View.GONE
                metaText.visibility = View.GONE
                waveText.visibility = View.VISIBLE
                waveText.text = HudLayouts.gwave(snap.listeningAmplitude)
                partialText.visibility = View.VISIBLE
                partialText.text = flatten(snap.listeningPartialRuns)
                cardTitle.visibility = View.GONE
                cardBody.visibility = View.GONE
                cardFooter.visibility = View.VISIBLE
                cardFooter.text = "click to send · ${snap.listeningElapsedSec}s"
            }
            AppState.Thinking -> {
                iconText.visibility = View.VISIBLE
                iconText.text = "${snap.icon.ifEmpty { "⌛" }} ${flatten(snap.detailRuns).ifEmpty { "thinking…" }}"
                detailText.visibility = View.GONE
                metaText.visibility = View.VISIBLE
                metaText.text = flatten(snap.metaRuns)
                waveText.visibility = View.GONE
                partialText.visibility = View.GONE
                cardTitle.visibility = View.GONE
                cardBody.visibility = View.GONE
                cardScroll.visibility = View.GONE
                cardFooter.visibility = View.GONE
            }
            AppState.Card -> {
                iconText.visibility = View.GONE
                detailText.visibility = View.GONE
                metaText.visibility = View.GONE
                waveText.visibility = View.GONE
                partialText.visibility = View.GONE
                cardTitle.visibility = View.VISIBLE
                cardTitle.text = flatten(snap.cardTitleRuns).ifEmpty { "Card" }
                cardBody.visibility = View.VISIBLE
                cardBody.text = snap.cardBodyText
                if (snap.cardScrollTotal > 1) {
                    cardScroll.visibility = View.VISIBLE
                    cardScroll.text = "▼ ${snap.cardScrollPos} / ${snap.cardScrollTotal}"
                } else {
                    cardScroll.visibility = View.GONE
                }
                cardFooter.visibility = View.VISIBLE
                cardFooter.text = HudLayouts.cardFooter(snap.cardOptions)
            }
            AppState.Insight -> {
                iconText.visibility = View.GONE
                detailText.visibility = View.GONE
                metaText.visibility = View.GONE
                waveText.visibility = View.GONE
                partialText.visibility = View.GONE
                cardTitle.visibility = View.VISIBLE
                cardTitle.text = "✦ ${flatten(snap.insightTitleRuns).ifEmpty { "Insight" }}"
                cardBody.visibility = View.VISIBLE
                cardBody.text = flatten(snap.insightBodyRuns)
                cardScroll.visibility = View.GONE
                cardFooter.visibility = View.VISIBLE
                cardFooter.text = "click to engage · ${snap.insightTtlSec}s"
            }
            AppState.Offline -> {
                iconText.visibility = View.VISIBLE
                iconText.text = "● offline · reconnecting…"
                iconText.setTextColor(Color.parseColor("#FF7C7C"))
                detailText.visibility = View.GONE
                metaText.visibility = View.GONE
                waveText.visibility = View.GONE
                partialText.visibility = View.GONE
                cardTitle.visibility = View.GONE
                cardBody.visibility = View.GONE
                cardScroll.visibility = View.GONE
                cardFooter.visibility = View.GONE
            }
        }
        // Reset icon color back to green when not Offline.
        if (snap.appState != AppState.Offline) {
            iconText.setTextColor(Color.parseColor("#00FF00"))
        }
    }

    private fun flatten(arr: JSONArray?): String =
        StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(arr)).first

    // ── view tree ───────────────────────────────────────────────────────

    private fun buildView(): LinearLayout {
        // 480×640 portrait: 16dp top padding, 200dp bottom (leave Halo Ring pip
        // region clear), 20dp horizontal.
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(200))
            gravity = Gravity.TOP
            setBackgroundColor(Color.parseColor("#050A06"))
        }
        iconText = mkText(textSize = 22f, color = "#00FF00", bold = true, gone = true)
        detailText = mkText(textSize = 16f, color = "#00CC00", paddingTop = 8, gone = true)
        metaText = mkText(textSize = 13f, color = "#008800", paddingTop = 8, gone = true)
        waveText = mkText(textSize = 22f, color = "#00FF66", paddingTop = 10, gone = true).also {
            it.typeface = android.graphics.Typeface.MONOSPACE
        }
        partialText = mkText(textSize = 16f, color = "#88FFAA", paddingTop = 10, gone = true)
        cardTitle = mkText(textSize = 22f, color = "#00FF00", bold = true, gone = true)
        cardBody = mkText(textSize = 16f, color = "#00CC00", paddingTop = 8, gone = true)
        cardScroll = mkText(textSize = 12f, color = "#008800", paddingTop = 6, gone = true)
        cardFooter = mkText(textSize = 12f, color = "#008800", paddingTop = 14, gone = true)
        root.addView(iconText)
        root.addView(detailText)
        root.addView(metaText)
        root.addView(waveText)
        root.addView(partialText)
        root.addView(cardTitle)
        root.addView(cardBody)
        root.addView(cardScroll)
        root.addView(cardFooter)
        return root
    }

    private fun mkText(textSize: Float, color: String, bold: Boolean = false,
                       paddingTop: Int = 0, gone: Boolean = false): TextView =
        TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            if (paddingTop > 0) setPadding(0, dp(paddingTop), 0, 0)
            if (gone) visibility = View.GONE
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
