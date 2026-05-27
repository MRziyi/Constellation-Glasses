package com.constellation.glass.app.ui

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

/** Static info screen (P-app.C). No focusable rows — read-only. */
@Composable
fun AboutScreen(
    appVersion: String,
    flavor: String,
    cortexConnected: Boolean,
) {
    AppChrome(title = "ABOUT", cortexConnected = cortexConnected) {
        Column0 {
            BasicText(
                text = "Constellation",
                style = TextStyle(
                    fontSize = HudTheme.titleSize,
                    fontWeight = FontWeight.Bold,
                    color = HudTheme.fg,
                ),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = "v$appVersion · flavor: $flavor",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
            Spacer(Modifier.height(16.dp))

            BasicText(
                text = "A constellation of senses, one mind.",
                style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = "「万象皆星，一念至此」",
                style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
            )
            Spacer(Modifier.height(16.dp))

            BasicText(
                text = "by Zack 紫意",
                style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
            )
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = "Halo Ring (companion smart ring) at",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
            BasicText(
                text = "github.com/MRziyi/Halo-Ring",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )

            Spacer(Modifier.height(16.dp))
            BasicText(
                text = "Free & open source.",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
            BasicText(
                text = "If you paid for this app you were scammed.",
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
        }
    }
}

@Composable
private fun Column0(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.padding(horizontal = ScreenPadding),
    ) { content() }
}

@Preview(name = "About", widthDp = 480, heightDp = 640, backgroundColor = 0xFF000000, showBackground = true)
@Composable
private fun PreviewAbout() {
    AboutScreen(
        appVersion = "0.2.0-pivot-baremetal",
        flavor = "phoneDebug",
        cortexConnected = true,
    )
}
