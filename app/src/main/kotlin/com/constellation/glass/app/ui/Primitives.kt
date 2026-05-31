package com.constellation.glass.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.constellation.glass.hud.HudTheme

/**
 * Reusable Compose primitives for the in-app settings UI. Modelled after
 * `Halo-Ring/app-project/app/src/main/kotlin/com/halo/ring/ui/Components.kt`
 * but stripped of Material3 (we use [BasicText] + plain [Box]/[Row]) to keep
 * the APK small per [HudTheme] no-Material3 rule.
 *
 * All elements obey [HudTheme] tokens — no inline colour / size / weight.
 */

/** Standard edge padding for full-screen content blocks. */
val ScreenPadding = 20.dp

/** Subtle horizontal hairline between list rows. */
@Composable
fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HudTheme.fgDim.copy(alpha = 0.15f)))
}

/**
 * The focus-indicator wrapper used by every settings row.
 *
 * Visual: when [focused], the row gets a 2-dp left bar in [HudTheme.fg] + a
 * ~7% green tint background. Otherwise just the row content.
 *
 * Two focus sources:
 *  1. **System** (via `Modifier.clickable` → implicitly focusable; DPAD up/down
 *     traverses; DPAD_CENTER fires `onClick`)
 *  2. **Caller** ([focused] parameter, used when we want to programmatically
 *     highlight on screen open)
 *
 * `onFocusChanged` keeps the compose-focus tracking in sync so the visual
 * indicator reflects whichever channel drove focus.
 */
@Composable
fun FocusableRow(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    var composeFocused by remember { mutableStateOf(false) }
    val effective = focused || composeFocused
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { composeFocused = it.isFocused }
            .clickable(onClick = onClick)
            .focusBar(effective)
            .padding(horizontal = ScreenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content,
    )
}

/** Left-edge bar + tint when focused. Private impl detail. */
private fun Modifier.focusBar(focused: Boolean): Modifier =
    if (focused) {
        this
            .background(HudTheme.fg.copy(alpha = 0.07f))
            .border(BorderStroke(2.dp, HudTheme.fg))
    } else this

/**
 * A primary-action button. Outlined by default; a brighter fill when focused.
 *
 * @param danger render with the warning amber so destructive actions stand out.
 */
@Composable
fun Cta(
    text: String,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit = {},
) {
    var composeFocused by remember { mutableStateOf(false) }
    val effective = focused || composeFocused
    val accent = if (danger) HudTheme.fgError else HudTheme.fg
    val bg = if (effective) accent.copy(alpha = 0.18f) else Color.Transparent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { composeFocused = it.isFocused }
            .clickable(onClick = onClick)
            .background(bg)
            .border(BorderStroke(if (effective) 2.dp else 1.dp, accent))
            .padding(vertical = 12.dp, horizontal = ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontSize = HudTheme.bodySize,
                fontWeight = FontWeight.Bold,
                color = accent,
            ),
        )
    }
}

/**
 * The thin connection-status dot shown in the top chrome of every screen.
 * Green = connected; amber = offline / reconnecting.
 */
@Composable
fun ConnectionDot(connected: Boolean) {
    Box(
        Modifier.size(8.dp).clip(CircleShape).background(
            if (connected) HudTheme.fg else HudTheme.fgError,
        ),
    )
}

/** Single-line label + value row. Used for `Status: ● connected` style displays. */
@Composable
fun ListRow(
    key: String,
    value: String,
    focused: Boolean = false,
    valueColor: Color = HudTheme.fg,
    onClick: () -> Unit = {},
) {
    FocusableRow(focused = focused, onClick = onClick) {
        BasicText(
            text = key,
            style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fgDim),
        )
        BasicText(
            text = value,
            style = TextStyle(fontSize = HudTheme.bodySize, color = valueColor),
        )
    }
    RowDivider()
}

/** Drill-in row: label on the left, optional right hint + chevron, navigates on click. */
@Composable
fun DrillRow(
    label: String,
    focused: Boolean = false,
    onClick: () -> Unit = {},
) {
    FocusableRow(focused = focused, onClick = onClick) {
        BasicText(
            text = label,
            style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fg),
        )
        BasicText(
            text = "›",
            style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fgDim),
        )
    }
    RowDivider()
}
