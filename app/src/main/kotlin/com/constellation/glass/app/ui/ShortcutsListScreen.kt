package com.constellation.glass.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.constellation.glass.ShortcutsClient
import com.constellation.glass.hud.HudTheme

/**
 * Settings → Shortcuts list (P-app.D.3).
 *
 * Each row shows the shortcut name + a short summary of its capture mode
 * ("with photo" / no qualifier). CLICK on a row → drill into editor.
 * Footer has a "+ NEW SHORTCUT" CTA that drills into the editor with a
 * blank shortcut. If the list is empty, the body explains what shortcuts
 * are + still offers the NEW CTA.
 */
@Composable
fun ShortcutsListScreen(
    shortcuts: List<ShortcutsClient.Shortcut>,
    loading: Boolean,
    error: String? = null,
    cortexConnected: Boolean,
    onPick: (ShortcutsClient.Shortcut) -> Unit,
    onNew: () -> Unit,
) {
    AppChrome(title = "SHORTCUTS", cortexConnected = cortexConnected) {
        BasicText(
            text = "Shortcuts",
            style = TextStyle(
                fontSize = HudTheme.titleSize,
                fontWeight = FontWeight.Bold,
                color = HudTheme.fg,
            ),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            text = "One-tap prompts. Skip the voice step.",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        Spacer(Modifier.height(12.dp))

        when {
            loading -> EmptyHint("Loading…")
            error != null -> EmptyHint("Couldn't load: $error", isError = true)
            shortcuts.isEmpty() -> EmptyHint(
                "No shortcuts yet.\nTap + NEW SHORTCUT below to add one — a preset prompt\n" +
                    "(optionally with a fresh camera frame) that fires on a single\n" +
                    "Halo Ring gesture or app tap.",
            )
            else -> Column(Modifier.fillMaxWidth()) {
                shortcuts.forEach { sc ->
                    ShortcutRow(sc, onClick = { onPick(sc) })
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Box(Modifier.padding(horizontal = ScreenPadding)) {
            Cta(text = "+ NEW SHORTCUT", onClick = onNew)
        }

        Spacer(Modifier.height(8.dp))
        BasicText(
            text = if (shortcuts.isEmpty()) "—" else "${shortcuts.size} saved",
            style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

@Composable
private fun ShortcutRow(sc: ShortcutsClient.Shortcut, onClick: () -> Unit) {
    FocusableRow(onClick = onClick) {
        Column {
            BasicText(
                text = sc.name,
                style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
            )
            BasicText(
                text = if (sc.photo) "with photo" else "text only",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        BasicText("›", style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fgDim))
    }
    RowDivider()
}

@Composable
private fun EmptyHint(text: String, isError: Boolean = false) {
    BasicText(
        text = text,
        style = TextStyle(
            fontSize = HudTheme.metaSize,
            color = if (isError) HudTheme.fgError else HudTheme.fgDim,
        ),
        modifier = Modifier.padding(horizontal = ScreenPadding, vertical = 8.dp),
    )
}

// ── Previews ────────────────────────────────────────────────────────────

private fun sampleShortcut(id: String, name: String, photo: Boolean): ShortcutsClient.Shortcut =
    ShortcutsClient.Shortcut(
        id = id, name = name, prompt = "...", photo = photo,
        created = "2026-05-26", updated = "2026-05-26",
    )

@Preview(name = "Shortcuts — 3 items", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewShortcutsList() {
    ShortcutsListScreen(
        shortcuts = listOf(
            sampleShortcut("whats-in-front", "What's in front of me?", true),
            sampleShortcut("quick-capture-person", "Quick capture person", true),
            sampleShortcut("ocr-save-to-today", "OCR & save to today", true),
        ),
        loading = false,
        cortexConnected = true,
        onPick = {}, onNew = {},
    )
}

@Preview(name = "Shortcuts — empty", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewShortcutsEmpty() {
    ShortcutsListScreen(
        shortcuts = emptyList(),
        loading = false,
        cortexConnected = true,
        onPick = {}, onNew = {},
    )
}

@Preview(name = "Shortcuts — error", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewShortcutsError() {
    ShortcutsListScreen(
        shortcuts = emptyList(),
        loading = false,
        error = "HTTP 503 from edge",
        cortexConnected = false,
        onPick = {}, onNew = {},
    )
}
