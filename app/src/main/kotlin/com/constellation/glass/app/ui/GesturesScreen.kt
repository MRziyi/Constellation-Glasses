package com.constellation.glass.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.GestureBindings
import com.constellation.glass.hud.HudTheme

/**
 * Settings → Gestures: rebind each card decision to a ring gesture.
 *
 * Tap a row to CYCLE through the available gestures (the glass UI has no
 * dropdown — same cycle-on-tap pattern as the rest of the settings). Defaults
 * (Zack 2026-06-02): tap+up approve · long-press modify · tap+down kill — bare
 * tap/double-tap retired as mis-trigger-prone. Bare swipe up/down stays scroll.
 */
@Composable
fun GesturesScreen(
    cortexConnected: Boolean,
    initial: Map<GestureBindings.Action, String>,
    onRebind: (GestureBindings.Action, String) -> Unit,
) {
    var bindings by remember { mutableStateOf(initial) }
    AppChrome(title = "GESTURES", cortexConnected = cortexConnected) {
        BasicText(
            text = "Card gestures",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = "Tap a row to cycle its gesture.\nBare swipe up/down still scrolls long cards.",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth()) {
            GestureBindings.Action.values().forEach { action ->
                val gesture = bindings[action] ?: GestureBindings.gestureFor(action)
                GestureRow(
                    action = action,
                    gesture = gesture,
                    onCycle = {
                        val opts = GestureBindings.SELECTABLE
                        val next = opts[(opts.indexOf(gesture) + 1) % opts.size]
                        bindings = bindings.toMutableMap().apply { put(action, next) }
                        onRebind(action, next)
                    },
                )
            }
        }
    }
}

@Composable
private fun GestureRow(
    action: GestureBindings.Action,
    gesture: String,
    onCycle: () -> Unit,
) {
    FocusableRow(onClick = onCycle) {
        Column {
            BasicText(
                text = actionLabel(action),
                style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
            )
            BasicText(
                text = GestureBindings.label(gesture),
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    RowDivider()
}

private fun actionLabel(action: GestureBindings.Action): String = when (action) {
    GestureBindings.Action.APPROVE -> "Send / approve"
    GestureBindings.Action.KILL -> "Reject / kill"
    GestureBindings.Action.MODIFY -> "Modify"
}

@Preview(name = "Gestures", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewGestures() {
    GesturesScreen(
        cortexConnected = true,
        initial = mapOf(
            GestureBindings.Action.APPROVE to "TAP_SWIPE_UP",
            GestureBindings.Action.KILL to "TAP_SWIPE_DOWN",
            GestureBindings.Action.MODIFY to "LONG_PRESS",
        ),
        onRebind = { _, _ -> },
    )
}
