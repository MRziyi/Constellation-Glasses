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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.app.NavRoute
import com.constellation.glass.hud.HudTheme

/** Snapshot of connection-related state that ConnectScreen renders. */
data class ConnectionInfo(
    val endpoint: String,
    val connected: Boolean,
    val cookiePersisted: Boolean,
    val lastInvokeAgo: String,
    /** When set, displayed as a transient toast row below the CTA (cleared after a few seconds). */
    val toast: String? = null,
)

/**
 * Connect-to-Cortex screen — the CONNECTION MANAGER (Zack 2026-05-31).
 *
 * Distinct from the Main screen's status card (which just summarises the live
 * connection): this is where you *change* the connection — primary action is
 * SCAN QR to re-pair / switch server (endpoint + cookie both come from the web
 * console's pairing QR). Below: live status, the current endpoint (drills to a
 * manual URL editor for debugging), and TEST CONNECTION. No logout (first
 * pairing is permanent unless you re-pair).
 */
@Composable
fun ConnectScreen(
    info: ConnectionInfo,
    onNavigate: (NavRoute) -> Unit,
    onRescan: () -> Unit,
    onTestConnection: () -> Unit,
) {
    AppChrome(title = "CONNECT", cortexConnected = info.connected) {
        BasicText(
            text = "Connect to Cortex",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = "Scan a pairing QR to switch server or refresh login.",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(14.dp))

        // PRIMARY: re-pair / switch server via the web-console QR.
        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = "SCAN QR · RE-PAIR", onClick = onRescan)
        }

        Spacer(Modifier.height(16.dp))

        ListRow(
            key = "Status",
            value = if (info.connected) "● connected" else "● offline",
            valueColor = if (info.connected) HudTheme.fg else HudTheme.fgError,
        )
        ListRow(
            key = "Cookie",
            value = if (info.cookiePersisted) "persisted ✓" else "missing ✗",
            valueColor = if (info.cookiePersisted) HudTheme.fg else HudTheme.fgError,
        )
        ListRow(
            key = "Last invoke",
            value = info.lastInvokeAgo,
            valueColor = HudTheme.fgDim,
        )

        Spacer(Modifier.height(12.dp))

        // Current endpoint — read-only display; drills to a manual URL editor
        // (advanced / debug — normally the QR sets this).
        FocusableRow(
            modifier = Modifier.border(width = 1.dp, color = HudTheme.fgDim.copy(alpha = 0.4f)),
            onClick = { onNavigate(NavRoute.EditEndpoint) },
        ) {
            BasicText(
                text = info.endpoint.ifBlank { "not paired" },
                style = TextStyle(
                    fontSize = HudTheme.footerSize,
                    fontFamily = FontFamily.Monospace,
                    color = HudTheme.fgDim,
                ),
                modifier = Modifier.padding(end = 8.dp),
            )
            BasicText("✎", style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim))
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = "TEST CONNECTION", onClick = onTestConnection)
        }

        if (info.toast != null) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                text = info.toast,
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fg),
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }
    }
}

@Preview(name = "Connect — happy", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewConnectHappy() {
    ConnectScreen(
        info = ConnectionInfo(
            endpoint = "wss://edge.example.com/ws/glass",
            connected = true,
            cookiePersisted = true,
            lastInvokeAgo = "3 min ago",
        ),
        onNavigate = {},
        onRescan = {},
        onTestConnection = {},
    )
}

@Preview(name = "Connect — offline + toast", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewConnectOffline() {
    ConnectScreen(
        info = ConnectionInfo(
            endpoint = "wss://edge.example.com/ws/glass",
            connected = false,
            cookiePersisted = true,
            lastInvokeAgo = "12 min ago",
            toast = "✓ ping ok · server_bound · tool_conn",
        ),
        onNavigate = {},
        onRescan = {},
        onTestConnection = {},
    )
}
