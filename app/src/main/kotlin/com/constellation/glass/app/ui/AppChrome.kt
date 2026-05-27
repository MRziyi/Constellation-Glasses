package com.constellation.glass.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.constellation.glass.hud.HudTheme

/**
 * The screen frame used by every settings page. Provides:
 *
 *   - Top status row: connection dot + "Constellation" + page-title (right-aligned)
 *   - Vertical content slot below
 *
 * Background is opaque black for the in-app UI (vs. transparent for the HUD).
 * Settings screens deliberately occlude the AR pass-through — when you're
 * configuring, the world dimming is intentional.
 */
@Composable
fun AppChrome(
    title: String,
    cortexConnected: Boolean,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = ScreenPadding, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(connected = cortexConnected)
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = "Constellation",
                    style = TextStyle(
                        fontSize = HudTheme.metaSize,
                        fontWeight = FontWeight.Bold,
                        color = HudTheme.fg,
                    ),
                )
            }
            BasicText(
                text = title,
                style = TextStyle(fontSize = HudTheme.footerSize, color = HudTheme.fgDim),
            )
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}
