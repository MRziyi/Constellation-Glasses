package com.constellation.glass.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.app.EndpointStore
import com.constellation.glass.hud.HudTheme

/**
 * Endpoint URL editor. Modal-like screen (single field + SAVE/CANCEL).
 *
 * The form starts pre-populated with the current endpoint so the user can
 * tweak a port / path without retyping the whole URL. SAVE persists to
 * [EndpointStore] and fires [onSaved] (caller writes DataStore + nudges
 * `ConstellationService` to reconnect WSS).
 */
@Composable
fun EditEndpointScreen(
    currentEndpoint: String,
    cortexConnected: Boolean,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf(currentEndpoint) }
    val valid = EndpointStore.looksValid(draft)

    AppChrome(title = "EDIT", cortexConnected = cortexConnected) {
        BasicText(
            text = "Cortex endpoint",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(4.dp))
        BasicText(
            text = "Full WSS URL — e.g. wss://your.edge.example/ws/glass",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(14.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding)
                .border(width = 1.dp, color = if (valid) HudTheme.fg else HudTheme.fgError)
                .padding(10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                textStyle = TextStyle(
                    fontSize = HudTheme.bodySize,
                    color = HudTheme.fg,
                    fontFamily = FontFamily.Monospace,
                ),
                singleLine = true,
                cursorBrush = SolidColor(HudTheme.fg),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!valid) {
            Spacer(Modifier.height(6.dp))
            BasicText(
                text = "URL must start with wss:// or ws://",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgError),
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            Box(Modifier.weight(1f)) {
                Cta(text = "CANCEL", onClick = onCancel)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                Cta(
                    text = "SAVE",
                    onClick = { if (valid) onSaved(draft.trim()) },
                )
            }
        }
    }
}

@Preview(name = "EditEndpoint", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewEditEndpoint() {
    EditEndpointScreen(
        currentEndpoint = "wss://edge.example.com/ws/glass",
        cortexConnected = true,
        onSaved = {},
        onCancel = {},
    )
}
