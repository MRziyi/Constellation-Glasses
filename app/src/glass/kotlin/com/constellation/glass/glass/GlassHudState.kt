package com.constellation.glass.glass

import com.constellation.glass.state.AppState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Process-wide snapshot of what the HUD should currently render. The Service
 * pushes updates into this; [GlassHudActivity] collects and re-renders.
 *
 * Decouples HUD state from any specific Activity instance — Activity can be
 * destroyed (double-click back) and recreated (next primary click) without
 * losing what was on screen, as long as the Service hasn't transitioned.
 */
object GlassHudState {

    /** Latest rendered HUD snapshot. */
    data class Snapshot(
        val appState: AppState = AppState.Idle,
        val icon: String = "",
        val detailRuns: JSONArray? = null,
        val metaRuns: JSONArray? = null,
        val cardId: String? = null,
        val cardTitleRuns: JSONArray? = null,
        val cardBodyRuns: JSONArray? = null,
        val cardOptions: List<String> = emptyList(),
        val insightTitleRuns: JSONArray? = null,
        val insightBodyRuns: JSONArray? = null,
        val insightTtlSec: Int = 8,
        val listeningElapsedSec: Int = 0,
        val listeningAmplitude: Float = 0f,
        val listeningPartialRuns: JSONArray? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun update(transform: Snapshot.() -> Snapshot) {
        _snapshot.value = _snapshot.value.transform()
    }
}
