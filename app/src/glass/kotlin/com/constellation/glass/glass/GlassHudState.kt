package com.constellation.glass.glass

import com.constellation.glass.hud.HudSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide snapshot holder for the glass-flavor HUD. The Service pushes
 * updates here; [com.constellation.glass.glass.hud.GlassHudActivity] collects
 * and re-renders.
 *
 * Decouples HUD state from any specific Activity instance — Activity can be
 * destroyed (DOUBLE_CLICK back, per Rokid system semantics) and recreated
 * (next primary click) without losing what was on screen, as long as the
 * Service hasn't transitioned.
 *
 * **P1.6**: the [HudSnapshot] data class now lives in `main/` so the shared
 * Compose renderer ([com.constellation.glass.hud.composables.AppStateHud])
 * can consume it. The StateFlow holder stays here (glass flavor) because
 * phoneDebug has its own equivalent and lifecycle.
 */
object GlassHudState {

    private val _snapshot = MutableStateFlow(HudSnapshot())
    val snapshot: StateFlow<HudSnapshot> = _snapshot.asStateFlow()

    fun update(transform: HudSnapshot.() -> HudSnapshot) {
        _snapshot.value = _snapshot.value.transform()
    }
}
