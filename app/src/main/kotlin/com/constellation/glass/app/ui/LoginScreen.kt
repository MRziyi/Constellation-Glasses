package com.constellation.glass.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.hud.HudTheme

/**
 * First-launch login. Replaces the pre-P-app.A inline View tree that
 * [com.constellation.glass.MainActivity] used to build.
 *
 * Layout: title + endpoint snippet + password field + SUBMIT cta + status
 * line. Once login succeeds, the app NEVER shows this again (per user
 * direction: no logout — cookie permanent).
 */
@Composable
fun LoginScreen(
    endpoint: String,
    status: String = "Enter password or scan the QR code from your web console.",
    busy: Boolean = false,
    onSubmit: (String) -> Unit,
    onScanQr: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = ScreenPadding, vertical = 24.dp),
    ) {
        BasicText(
            text = "Constellation",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            text = endpoint,
            style = TextStyle(
                fontSize = HudTheme.footerSize,
                fontFamily = FontFamily.Monospace,
                color = HudTheme.fgDim,
            ),
        )

        Spacer(Modifier.height(24.dp))

        BasicText(
            text = status,
            style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
        )

        Spacer(Modifier.height(16.dp))

        var pw by rememberSaveable { mutableStateOf("") }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(width = 1.dp, color = HudTheme.fg)
                .padding(12.dp),
        ) {
            BasicTextField(
                value = pw,
                onValueChange = { pw = it },
                textStyle = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                cursorBrush = SolidColor(HudTheme.fg),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(20.dp))

        Row {
            Box(Modifier.weight(1f)) {
                Cta(text = "SCAN QR", onClick = onScanQr)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                Cta(
                    text = if (busy) "AUTHORIZING…" else "AUTHORIZE",
                    onClick = { if (!busy && pw.isNotBlank()) onSubmit(pw) },
                )
            }
        }
    }
}

@Preview(name = "Login — initial", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewLogin() {
    LoginScreen(
        endpoint = "wss://edge.example.com/ws/glass",
        onSubmit = {},
    )
}

@Preview(name = "Login — bad password", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewLoginBadPw() {
    LoginScreen(
        endpoint = "wss://edge.example.com/ws/glass",
        status = "Bad password (HTTP 401). Try again.",
        onSubmit = {},
    )
}
