package com.constellation.glass.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import com.constellation.glass.ShortcutsClient
import com.constellation.glass.hud.HudTheme

/**
 * Shortcut editor (P-app.D.3). Used for both "new" (caller passes null for
 * `existing`) and "edit" (caller passes the existing record).
 *
 * Fields:
 *   - name (required)
 *   - prompt textarea (the literal text sent to Cortex)
 *   - photo toggle (capture a fresh camera frame and bundle?)
 *
 * The id field is auto-derived from the name on first save (slugified
 * kebab-case) — keeping it out of the editor avoids the user having to
 * understand the id concept. For existing shortcuts the id is immutable.
 *
 * SAVE button is enabled only when name + prompt are non-empty. DELETE
 * only appears in edit mode and uses the danger (amber) Cta variant.
 */
@Composable
fun ShortcutEditorScreen(
    existing: ShortcutsClient.Shortcut?,
    cortexConnected: Boolean,
    busy: Boolean,
    onSave: (name: String, prompt: String, photo: Boolean) -> Unit,
    onDelete: () -> Unit = {},
    onCancel: () -> Unit,
) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var prompt by rememberSaveable(existing?.id) { mutableStateOf(existing?.prompt.orEmpty()) }
    var photo by rememberSaveable(existing?.id) { mutableStateOf(existing?.photo ?: true) }
    val canSave = name.isNotBlank() && prompt.isNotBlank()

    AppChrome(title = if (existing == null) "NEW" else "EDIT", cortexConnected = cortexConnected) {
        Column(Modifier.padding(horizontal = ScreenPadding)) {

            BasicText(
                text = existing?.name?.takeIf { it.isNotBlank() } ?: "New shortcut",
                style = TextStyle(
                    fontSize = HudTheme.titleSize,
                    fontWeight = FontWeight.Bold,
                    color = HudTheme.fg,
                ),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = existing?.id?.let { "id: $it" } ?: "id will be derived from name",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )

            Spacer(Modifier.height(16.dp))

            LabelledField(label = "NAME") {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
                    singleLine = true,
                    cursorBrush = SolidColor(HudTheme.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            LabelledField(label = "PROMPT — sent to Cortex as your message", minHeight = 110.dp) {
                BasicTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    textStyle = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
                    cursorBrush = SolidColor(HudTheme.fg),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Photo toggle — Compose without Material3 means we hand-roll the visual.
            FocusableRow(
                modifier = Modifier.padding(horizontal = 0.dp),
                onClick = { photo = !photo },
            ) {
                Column {
                    BasicText(
                        text = "Capture photo",
                        style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
                    )
                    BasicText(
                        text = "Attach a fresh camera frame to the prompt",
                        style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ToggleDot(on = photo)
            }

            Spacer(Modifier.height(20.dp))

            Row {
                Box(Modifier.weight(1f)) {
                    Cta(text = "CANCEL", onClick = onCancel)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    Cta(
                        text = if (busy) "SAVING…" else "SAVE",
                        onClick = { if (canSave && !busy) onSave(name.trim(), prompt.trim(), photo) },
                    )
                }
            }

            if (existing != null) {
                Spacer(Modifier.height(8.dp))
                Cta(text = "DELETE", danger = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun LabelledField(
    label: String,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    Column {
        BasicText(
            text = label,
            style = TextStyle(
                fontSize = HudTheme.footerSize,
                color = HudTheme.fgDim,
                letterSpacing = 0.5.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .border(width = 1.dp, color = HudTheme.fg)
                .padding(10.dp),
        ) { content() }
    }
}

@Composable
private fun ToggleDot(on: Boolean) {
    Box(
        Modifier
            .padding(start = 12.dp)
            .border(width = 2.dp, color = if (on) HudTheme.fg else HudTheme.fgDim)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        BasicText(
            text = if (on) "ON" else "OFF",
            style = TextStyle(
                fontSize = HudTheme.metaSize,
                fontWeight = FontWeight.Bold,
                color = if (on) HudTheme.fg else HudTheme.fgDim,
            ),
        )
    }
}

// ── Previews ────────────────────────────────────────────────────────────

@Preview(name = "Editor — new", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewEditorNew() {
    ShortcutEditorScreen(
        existing = null,
        cortexConnected = true,
        busy = false,
        onSave = { _, _, _ -> }, onDelete = {}, onCancel = {},
    )
}

@Preview(name = "Editor — existing", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewEditorExisting() {
    ShortcutEditorScreen(
        existing = ShortcutsClient.Shortcut(
            id = "whats-in-front",
            name = "What's in front of me?",
            prompt = "Describe what's in the attached photo. One-sentence summary first, then two more sentences with any details that look interesting or unusual.",
            photo = true,
            created = "2026-05-26",
            updated = "2026-05-26",
        ),
        cortexConnected = true,
        busy = false,
        onSave = { _, _, _ -> }, onDelete = {}, onCancel = {},
    )
}
