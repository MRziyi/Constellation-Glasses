package com.constellation.glass.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * Cortex-connection STATUS screen (reached from the Main status card).
 *
 * Read-only: status / cookie / last-invoke + TEST CONNECTION. Nothing to edit
 * here (Zack 2026-05-31) — the endpoint comes from the pairing QR, and re-pairing
 * lives on the dedicated "重新配对" row of the Main screen, not here.
 */
@Composable
fun ConnectScreen(
    info: ConnectionInfo,
    onTestConnection: () -> Unit,
) {
    AppChrome(title = "CONNECT", cortexConnected = info.connected) {
        BasicText(
            text = "Cortex connection",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(14.dp))

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

        Spacer(Modifier.height(16.dp))

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
        onTestConnection = {},
    )
}
