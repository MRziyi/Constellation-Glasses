package com.constellation.glass.hud.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.constellation.glass.hud.HudTheme

/**
 * Floating card "frame" that wraps a HUD state's content.
 *
 * Visual: rounded-corner dark fill + faint dim-green border. On the
 * Rokid Glasses' monochrome-green panel, the dark fill is unlit (= AR
 * transparent), so wearers see only the border + lit text "floating" in
 * front of the world. On the phoneDebug simulator the dark fill is what
 * gives the card its visible shape on a phone screen.
 *
 * Sizing:
 *   - Width: wraps content but capped at [HudTheme.cardMaxWidthDp] so long
 *     bodies still fit on the 320-dp-wide eyewear content area.
 *   - Height: wraps content but capped at [HudTheme.cardMaxHeightDp].
 *     Short cards (e.g. "Listening") render compact; long cards
 *     (e.g. multi-paragraph Card body) grow up to the cap then rely on
 *     ScrollWindow / scrolling.
 *
 * Replaces the pre-2026-05-28 "fullscreen-transparent Activity with text
 * pinned to top-left" approach. That misled the wearer into seeing the
 * HUD as taking over the whole panel — now the HUD is a real overlay
 * that floats over whatever app is in front (per real-device feedback).
 */
@Composable
fun CardFrame(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .widthIn(max = HudTheme.cardMaxWidthDp)
            .heightIn(max = HudTheme.cardMaxHeightDp)
            .background(color = HudTheme.cardBg, shape = HudTheme.cardShape)
            .border(width = 1.dp, color = HudTheme.cardBorder, shape = HudTheme.cardShape)
            .padding(HudTheme.cardInnerPadding),
    ) {
        content()
    }
}
