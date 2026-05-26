package com.constellation.glass.hud.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.TextStyle
import com.constellation.glass.hud.HudTheme
import com.constellation.glass.hud.StyledRunsRenderer.StyledRun
import com.constellation.glass.hud.StyledRunsRenderer

/**
 * Renders a list of Cortex `StyledRun`s with per-run styling preserved.
 *
 * **Why this exists**: pre-P1.6 the renderer called `StyledRunsRenderer.flatten()`
 * which threw away per-run style info and emitted a single (text, dominantStyle)
 * pair — losing the ability to bold/italic/dim individual segments within a
 * sentence. With Compose's [AnnotatedString], each run becomes a [SpanStyle]
 * span and per-run styling actually renders.
 *
 * **Note**: uses [BasicText] not [Text] — we don't depend on Material3.
 */
@Composable
fun RunStyledText(
    runs: List<StyledRun>,
    baseSize: TextUnit = HudTheme.bodySize,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = runs.toAnnotatedString(),
        style = TextStyle(fontSize = baseSize),
        modifier = modifier,
    )
}

/**
 * Pure conversion — split out so callers that want to embed runs into a larger
 * AnnotatedString (e.g. with trailing meta) can compose them.
 */
fun List<StyledRun>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    forEach { r ->
        withStyle(
            SpanStyle(
                fontWeight = if (StyledRunsRenderer.isBold(r.style)) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (StyledRunsRenderer.isItalic(r.style)) FontStyle.Italic else FontStyle.Normal,
                color = if (StyledRunsRenderer.isDim(r.style)) HudTheme.fgDim else HudTheme.fg,
                fontFamily = if (StyledRunsRenderer.isCode(r.style)) FontFamily.Monospace else FontFamily.SansSerif,
            ),
        ) { append(r.text) }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Previews — open these in Android Studio's Compose Preview pane.
// ────────────────────────────────────────────────────────────────────────────

@Preview(
    name = "RunStyledText — mixed styles",
    widthDp = 300,
    heightDp = 80,
    backgroundColor = 0xFF000000,
    showBackground = true,
)
@Composable
private fun PreviewRunStyledText_mixed() {
    Box(Modifier.background(Color.Black).padding(8.dp)) {
        RunStyledText(
            runs = listOf(
                StyledRun("Hey ", "normal"),
                StyledRun("Jane", "bold"),
                StyledRun(" — see you at ", "normal"),
                StyledRun("3 PM", "bold"),
                StyledRun(".", "normal"),
            ),
        )
    }
}

@Preview(
    name = "RunStyledText — dim meta",
    widthDp = 300,
    heightDp = 60,
    backgroundColor = 0xFF000000,
    showBackground = true,
)
@Composable
private fun PreviewRunStyledText_dim() {
    Box(Modifier.background(Color.Black).padding(8.dp)) {
        RunStyledText(
            runs = listOf(
                StyledRun("via Claude Code → AppleScript Mail", "dim"),
            ),
            baseSize = HudTheme.metaSize,
        )
    }
}

@Preview(
    name = "RunStyledText — code + italic",
    widthDp = 360,
    heightDp = 80,
    backgroundColor = 0xFF000000,
    showBackground = true,
)
@Composable
private fun PreviewRunStyledText_codeItalic() {
    Box(Modifier.background(Color.Black).padding(8.dp)) {
        RunStyledText(
            runs = listOf(
                StyledRun("Wants to write to ", "normal"),
                StyledRun("secrets/", "code"),
                StyledRun(" — really, ", "normal"),
                StyledRun("really", "italic"),
                StyledRun("?", "normal"),
            ),
        )
    }
}
