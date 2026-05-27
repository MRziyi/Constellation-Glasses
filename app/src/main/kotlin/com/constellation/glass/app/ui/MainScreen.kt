package com.constellation.glass.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.app.NavRoute
import com.constellation.glass.hud.HudTheme

/** Live cortex status snapshot used by the Main screen status block. */
data class CortexStatus(
    val connected: Boolean = false,
    val endpoint: String = "—",
    val invokesTotal: Int = 0,
    val lastInvokeAgo: String = "—",
)

/**
 * Root settings screen — mockup §2.1.
 *
 * Layout: connection status block (focusable; CLICK drills to Connect) +
 * 3 navigation rows (Shortcuts / Connect / About) + bottom Halo Ring hint.
 */
@Composable
fun MainScreen(
    status: CortexStatus,
    shortcutCount: Int = 0,
    onNavigate: (NavRoute) -> Unit,
) {
    AppChrome(title = "MAIN", cortexConnected = status.connected) {
        StatusBlock(status = status, onClick = { onNavigate(NavRoute.Connect) })

        Spacer(Modifier.height(16.dp))

        DrillRow(
            label = "Shortcuts",
            rightHint = if (shortcutCount > 0) "$shortcutCount saved" else "—",
            onClick = { onNavigate(NavRoute.Shortcuts) },
        )
        DrillRow(
            label = "Connect to Cortex",
            onClick = { onNavigate(NavRoute.Connect) },
        )
        DrillRow(
            label = "About",
            onClick = { onNavigate(NavRoute.About) },
        )

        Spacer(Modifier.height(20.dp))

        BasicText(
            text = "Pair Halo Ring for ring-gesture shortcuts",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

/**
 * The connection status box at the top of MainScreen. A focusable row drawn
 * as a bordered card. CLICK or SWIPE + CLICK drills into [NavRoute.Connect].
 */
@Composable
private fun StatusBlock(
    status: CortexStatus,
    onClick: () -> Unit,
) {
    FocusableRow(
        modifier = Modifier
            .padding(horizontal = 0.dp)
            .border(width = 1.dp, color = HudTheme.fgDim.copy(alpha = 0.4f)),
        onClick = onClick,
    ) {
        Column(Modifier.padding(end = 8.dp)) {
            BasicText(
                text = if (status.connected) "● Connected to Cortex" else "● Offline",
                style = TextStyle(
                    fontSize = HudTheme.bodySize,
                    fontWeight = FontWeight.Bold,
                    color = if (status.connected) HudTheme.fg else HudTheme.fgError,
                ),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = status.endpoint,
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = "${status.invokesTotal} invokes · ${status.lastInvokeAgo}",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
        }
        BasicText("›", style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fgDim))
    }
}

@Preview(name = "Main — connected", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewMainConnected() {
    MainScreen(
        status = CortexStatus(
            connected = true,
            endpoint = "wss://edge.example.com/ws/glass",
            invokesTotal = 12,
            lastInvokeAgo = "3 min ago",
        ),
        shortcutCount = 3,
        onNavigate = {},
    )
}

@Preview(name = "Main — offline", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewMainOffline() {
    MainScreen(
        status = CortexStatus(connected = false, endpoint = "wss://edge.example.com/ws/glass"),
        shortcutCount = 0,
        onNavigate = {},
    )
}
