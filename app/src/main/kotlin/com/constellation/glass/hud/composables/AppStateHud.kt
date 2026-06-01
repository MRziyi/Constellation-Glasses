package com.constellation.glass.hud.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.hud.CardScrollBus
import com.constellation.glass.hud.HudLayouts
import com.constellation.glass.hud.HudSnapshot
import com.constellation.glass.hud.HudTheme
import com.constellation.glass.hud.StyledRunsRenderer
import com.constellation.glass.state.AppState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Top-level HUD renderer. Switches on [AppState] and delegates to per-state
 * composables. Shared between `glass` and `phoneDebug` flavors — both now
 * host this inside a SYSTEM_ALERT_WINDOW overlay (2026-05-28 pivot from
 * fullscreen Activity).
 *
 * Wraps every non-IDLE state in a [CardFrame] so the HUD shows as a real
 * floating card (rounded corners, faint border, dark fill on the simulator /
 * transparent unlit pixels on the eyewear). [AppState.Idle] renders nothing
 * so the overlay can be made invisible / removed when there's no content.
 */
@Composable
fun AppStateHud(snap: HudSnapshot, modifier: Modifier = Modifier) {
    if (snap.appState == AppState.Idle) {
        IdleHud()
        return
    }
    // Main HUD card on top; the decoupled 视觉抢拍 satellite card hangs BELOW it.
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (snap.appState) {
            AppState.Listening -> CardFrame { ListeningHud(snap) }
            AppState.Thinking  -> CardFrame { ThinkingHud(snap) }
            AppState.Card      -> CardFrame { CardHud(snap) }
            AppState.Insight   -> CardFrame { InsightHud(snap) }
            AppState.Offline   -> CardFrame { OfflineHud(snap) }
            AppState.AuthExpired -> CardFrame { AuthExpiredHud() }
            AppState.Idle      -> {}
        }
        // 视觉抢拍 (Zack 2026-06-01): a small, decoupled status card BELOW the main
        // HUD while a speculative capture is in flight — capturing → receiving ·
        // NN KB → dismissed on done. Independent of the main card / state / gestures.
        if (snap.satelliteVisible) {
            CardFrame {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = "${snap.satelliteIcon} ",
                        style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fg),
                    )
                    val runs = StyledRunsRenderer.parseRuns(snap.satelliteRuns)
                    if (runs.isNotEmpty()) {
                        RunStyledText(runs = runs, baseSize = HudTheme.metaSize)
                    }
                }
            }
        }
    }
}

// ── IDLE ────────────────────────────────────────────────────────────────────

@Composable
private fun IdleHud() {
    // Empty. HUD is dark. The Activity itself stays alive (so the next key
    // press can transition us instantly) but draws nothing.
}

// ── LISTENING ───────────────────────────────────────────────────────────────

@Composable
private fun ListeningHud(snap: HudSnapshot) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Status row: pulse dot + label + elapsed ──────────────────────────
        // The JBD4020 panel is monochrome-green; alpha (brightness) is the only
        // intensity knob, so the dot's brightness IS the loudness meter — it
        // replaces the old ASCII g-wave (real-device feedback "好丑", 2026-05-30).
        // Louder → brighter + slightly larger; silence → a faint resting dot.
        val amp = snap.listeningAmplitude.coerceIn(0f, 1f)
        val dotAlpha = 0.22f + 0.78f * amp
        val dotSize = (8f + 8f * amp).dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(HudTheme.fg.copy(alpha = dotAlpha)),
                )
            }
            Spacer(Modifier.width(8.dp))
            BasicText(
                text = "listening…",
                style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fg),
            )
            Spacer(Modifier.weight(1f))
            // Elapsed (mm:ss). Mic hard-caps at 15s (C-37); after 12s amber.
            val secs = snap.listeningElapsedSec
            val timeColor = if (secs >= 12) HudTheme.fgError else HudTheme.fgDim
            BasicText(
                text = "%d:%02d".format(secs / 60, secs % 60),
                style = TextStyle(fontSize = HudTheme.metaSize, color = timeColor),
            )
        }

        // ── Live partial transcript — now the visual subject ─────────────────
        val partial = StyledRunsRenderer.parseRuns(snap.listeningPartialRuns)
        if (partial.isNotEmpty()) {
            RunStyledText(runs = partial, baseSize = HudTheme.bodySize)
        }

        // The wearer ends the utterance with a TAP (not a timeout).
        BasicText(
            text = "TAP to stop",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
        )
    }
}

// ── THINKING ────────────────────────────────────────────────────────────────

@Composable
private fun ThinkingHud(snap: HudSnapshot) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val icon = if (snap.icon.isNotEmpty()) snap.icon else "💭"
        val detail = StyledRunsRenderer.parseRuns(snap.detailRuns)
        Row(verticalAlignment = Alignment.Top) {
            BasicText(
                text = "$icon ",
                style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fg),
            )
            if (detail.isNotEmpty()) {
                RunStyledText(runs = detail, baseSize = HudTheme.titleSize)
            } else {
                // Detail persists across frames + clears on Idle (GlassHudSurface),
                // so the only time it's empty is the brief moment right after you
                // approve, before cortex's first specific progress lands. Name THAT
                // honestly — never a vague black-box word (Zack 2026-06-01). (The
                // prompt-approved-but-awaiting-photo wait is its own labelled state,
                // "⏳ waiting for photo…", emitted by cortex — not this branch.)
                BasicText(
                    text = "sending your request…",
                    style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fg),
                )
            }
        }
        // Meta (e.g. "12s · 1.2k tokens") in dim
        val meta = StyledRunsRenderer.parseRuns(snap.metaRuns)
        if (meta.isNotEmpty()) {
            RunStyledText(runs = meta, baseSize = HudTheme.metaSize)
        }
    }
}

// ── CARD ────────────────────────────────────────────────────────────────────

@Composable
private fun CardHud(snap: HudSnapshot) {
    // F1 (2026-05-28): body wraps naturally by container width via Text's
    // built-in softWrap. The verticalScroll wrapper bounds body height to
    // [HudTheme.cardBodyMaxHeightDp] and allows overflow scrolling.
    // External 2F SWIPE input arrives via CardScrollBus → animateScrollBy.
    val scrollState = rememberScrollState()
    LaunchedEffect(snap.cardId) {
        // Reset scroll to top when a new card replaces the old one
        scrollState.scrollTo(0)
    }
    LaunchedEffect(Unit) {
        CardScrollBus.events.collect { cmd ->
            val delta = HudTheme.cardScrollPxPerSwipe
            when (cmd) {
                CardScrollBus.ScrollCmd.Down -> scrollState.animateScrollBy(delta)
                CardScrollBus.ScrollCmd.Up   -> scrollState.animateScrollBy(-delta)
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Echo / quote row — what the wearer said, dim, above the title. Gives
        // every card a "reply" framing (Zack 2026-05-30 redesign). Optional —
        // renders nothing when the frame carries no echo_runs.
        CardQuoteRow(snap.cardEchoRuns)

        // Title (the headline / result — the visual anchor)
        val title = StyledRunsRenderer.parseRuns(snap.cardTitleRuns)
        if (title.isNotEmpty()) {
            RunStyledText(runs = title, baseSize = HudTheme.titleSize)
        }

        // Body — naturally wrapped + verticalScroll
        if (snap.cardBodyText.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = HudTheme.cardBodyMaxHeightDp)
                    .verticalScroll(scrollState),
            ) {
                BasicText(
                    text = snap.cardBodyText,
                    style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
                )
            }
        }

        // Compose-side scroll indicator: shows ▲ above / ▼ below when there's
        // more content out of view in that direction. Avoids stale pos/total
        // page math from the pre-F1 ScrollWindow model.
        val canForward = scrollState.canScrollForward
        val canBackward = scrollState.canScrollBackward
        if (canForward || canBackward) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    text = buildString {
                        if (canBackward) append("▲ ")
                        if (canForward) append("▼")
                    }.trim(),
                    style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fgDim),
                )
            }
        }

        Spacer(Modifier.height(2.dp))

        // Footer — depends on whether the card is actionable or info-only,
        // plus whether there's scrollable content (adds a "2F SWIPE" hint).
        val swipeHint = if (canForward || canBackward) " · SWIPE scroll" else ""
        val footerText = if (snap.cardOptions.isNotEmpty()) {
            HudLayouts.cardFooter(snap.cardOptions) + swipeHint
        } else {
            // Final report card (notification). Its input was already
            // STT-reviewed before processing, so the result only needs
            // acknowledging — TAP OK. No redo, no kill, no dismiss
            // (Zack 2026-05-30, #4).
            "TAP OK$swipeHint"
        }
        // Footer row: action hint (left) + card source attribution (right).
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = footerText,
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
            if (snap.cardSource.isNotEmpty()) {
                BasicText(
                    text = snap.cardSource,
                    style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
                )
            }
        }
    }
}


/**
 * Dim quoted "echo" row — a thin left bar + the wearer's words in “ ”. Shared
 * framing so EVERY card type (notification / checkpoint / question / reply)
 * reads as a reply to something, not a bare statement (Zack 2026-05-30). Renders
 * nothing when there's no echo, so it's safe to call unconditionally.
 */
@Composable
private fun CardQuoteRow(echoRuns: JSONArray?) {
    if (echoRuns == null || echoRuns.length() == 0) return
    val (text, _) = StyledRunsRenderer.flatten(StyledRunsRenderer.parseRuns(echoRuns))
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(12.dp).background(HudTheme.fgDim))
        Spacer(Modifier.width(6.dp))
        BasicText(
            text = "“$text”",
            style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fgDim),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── INSIGHT ─────────────────────────────────────────────────────────────────

@Composable
private fun InsightHud(snap: HudSnapshot) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val title = StyledRunsRenderer.parseRuns(snap.insightTitleRuns)
        if (title.isNotEmpty()) {
            RunStyledText(runs = title, baseSize = HudTheme.titleSize)
        }
        val body = StyledRunsRenderer.parseRuns(snap.insightBodyRuns)
        if (body.isNotEmpty()) {
            RunStyledText(runs = body, baseSize = HudTheme.bodySize)
        }
        Spacer(Modifier.height(4.dp))
        // insightTtlSec > 0 = legacy timed insight (countdown bar). With ring
        // takeover the insight no longer auto-closes (ttlSec=0): show only the
        // ring affordance, no countdown.
        if (snap.insightTtlSec > 0) {
            val ttlMax = 8
            val frac = (snap.insightTtlSec.toFloat() / ttlMax.toFloat()).coerceIn(0f, 1f)
            TtlBar(fraction = frac)
            BasicText(
                text = "auto-close ${snap.insightTtlSec}s · tap to engage",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
        } else {
            BasicText(
                text = "TAP engage · 2×TAP kill",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
        }
    }
}

@Composable
private fun TtlBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(2.dp).background(HudTheme.fgDim.copy(alpha = 0.2f))) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(if (fraction < 0.25f) HudTheme.fgError else HudTheme.fg),
        )
    }
}

// ── OFFLINE ─────────────────────────────────────────────────────────────────

@Composable
private fun OfflineHud(snap: HudSnapshot) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            BasicText(
                text = "● ",
                style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fgError),
            )
            BasicText(
                text = "offline · reconnecting…",
                style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fg),
            )
        }
        val meta = StyledRunsRenderer.parseRuns(snap.metaRuns)
        if (meta.isNotEmpty()) {
            RunStyledText(runs = meta, baseSize = HudTheme.metaSize)
        } else {
            BasicText(
                text = "last seen 4s ago · retry in 2s",
                style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fgDim),
            )
        }
    }
}

// ── AUTH EXPIRED ──────────────────────────────────────────────────────────────

@Composable
private fun AuthExpiredHud() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            BasicText(
                text = "● ",
                style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fgError),
            )
            BasicText(
                text = "Session expired",
                style = TextStyle(fontSize = HudTheme.titleSize, color = HudTheme.fg),
            )
        }
        BasicText(
            text = "Long-press · scan to re-pair",
            style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fg),
        )
        BasicText(
            text = "Double-tap · dismiss",
            style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fgDim),
        )
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Previews — one per AppState at the real Rokid Glasses 480×640 panel size.
// ────────────────────────────────────────────────────────────────────────────

private fun runs(vararg pairs: Pair<String, String>): JSONArray =
    JSONArray().apply {
        pairs.forEach { (text, style) ->
            put(JSONObject().apply { put("text", text); put("style", style) })
        }
    }

@Preview(name = "1. Idle (HUD off)", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewIdle() {
    AppStateHud(HudSnapshot(appState = AppState.Idle))
}

@Preview(name = "2. Listening (mid-utterance)", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewListening() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Listening,
            listeningElapsedSec = 4,
            listeningAmplitude = 0.7f,
            listeningPartialRuns = runs(
                "remind me to " to "normal",
                "review Jane's CHI draft" to "bold",
                " tomorrow at 3" to "normal",
            ),
        ),
    )
}

@Preview(name = "3. Listening (near 15s cap, amber)", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewListeningAmber() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Listening,
            listeningElapsedSec = 13,
            listeningAmplitude = 0.4f,
            listeningPartialRuns = runs("…and also send it to Mike" to "normal"),
        ),
    )
}

@Preview(name = "4. Thinking", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewThinking() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Thinking,
            icon = "💭",
            detailRuns = runs("drafting reply to " to "normal", "Jane" to "bold", "…" to "normal"),
            metaRuns = runs("via Mail · 3s" to "dim"),
        ),
    )
}

@Preview(name = "5. Card — Preview action", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewCardPreviewAction() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Card,
            cardTitleRuns = runs("✉ " to "normal", "Send to Jane" to "bold"),
            cardBodyText = """via Mail (you@example.com)

Hey Jane — yes, see you at 3.
Looking forward to it.

– Zack""",
            cardScrollPos = 1,
            cardScrollTotal = 1,
            cardOptions = listOf("approve", "modify", "kill"),
        ),
    )
}

@Preview(name = "5b. Card — info only (no buttons)", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewCardInfoOnly() {
    AppStateHud(
        GlassHudState_Snapshot_battery(),
    )
}

private fun GlassHudState_Snapshot_battery(): HudSnapshot = HudSnapshot(
    appState = AppState.Card,
    cardTitleRuns = runs("🔋 " to "normal", "Battery" to "bold"),
    cardBodyText = """plugged: true
percent: 79
estimate: 2h 14m to 100%""",
    cardOptions = emptyList(),  // ← info-only — footer becomes "double-click to dismiss"
)

@Preview(name = "6. Card — long body (scroll)", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewCardScrolling() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Card,
            cardTitleRuns = runs("⚙ " to "normal", "Claude Code needs you" to "bold"),
            cardBodyText = """editing R08-dev/auth.ts

Wants to write to secrets/
directory. Allow once, for
this session, or deny?""",
            cardScrollPos = 2,
            cardScrollTotal = 4,
            cardOptions = listOf("approve", "modify", "kill"),
        ),
    )
}

@Preview(name = "7. Insight (TTL 4s left)", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewInsight() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Insight,
            insightTitleRuns = runs("✦ " to "normal", "Surprising insight" to "bold"),
            insightBodyRuns = runs(
                "You said you'd review Jane's paper by " to "normal",
                "4/30 — tomorrow" to "bold",
                ". Open the draft now?" to "normal",
            ),
            insightTtlSec = 4,
        ),
    )
}

@Preview(name = "8. Offline", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewOffline() {
    AppStateHud(
        HudSnapshot(
            appState = AppState.Offline,
            metaRuns = runs("last seen 4s ago · retry in 2s" to "dim"),
        ),
    )
}
