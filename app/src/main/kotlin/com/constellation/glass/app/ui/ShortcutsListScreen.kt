package com.constellation.glass.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.constellation.glass.ShortcutSlots
import com.constellation.glass.hud.HudTheme

/**
 * Settings → Shortcuts: a READ-ONLY view of the three fixed slots
 * (shortcut1/2/3). Slots are edited by VOICE — "set shortcut 2 to send a photo
 * and ask what's in front" — not in this screen; this is just so the wearer can
 * see what each slot currently does + which ring action id to bind.
 */
@Composable
fun ShortcutsListScreen(
    slots: List<ShortcutSlots.Slot>,
    cortexConnected: Boolean,
) {
    AppChrome(title = "SHORTCUTS", cortexConnected = cortexConnected) {
        BasicText(
            text = "Shortcut slots",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = "Edit by voice: \"set shortcut 2 to ask what's in front\".\nBind shortcut1/2/3 to a ring gesture in Halo Ring.",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(12.dp))

        Column(Modifier.fillMaxWidth()) {
            slots.forEach { slot -> SlotRow(slot) }
        }
    }
}

@Composable
private fun SlotRow(slot: ShortcutSlots.Slot) {
    FocusableRow(onClick = {}) {
        Column {
            BasicText(
                text = "Shortcut ${slot.index}: ${slot.label}",
                style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
            )
            BasicText(
                text = when {
                    !slot.configured -> "empty — set by voice"
                    slot.sendPhoto -> "📷 \"${slot.prompt}\""
                    else -> "\"${slot.prompt}\""
                },
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
    RowDivider()
}

// ── Preview ─────────────────────────────────────────────────────────────

@Preview(name = "Shortcut slots", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewSlots() {
    ShortcutsListScreen(
        slots = listOf(
            ShortcutSlots.Slot(1, "What's in front", "What's in front of me?", sendPhoto = true),
            ShortcutSlots.Slot(2, "Shortcut 2", "", sendPhoto = false),
            ShortcutSlots.Slot(3, "Read this", "Read the text in front of me.", sendPhoto = true),
        ),
        cortexConnected = true,
    )
}
