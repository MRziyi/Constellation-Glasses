package com.constellation.glass.glass.hud

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.constellation.glass.glass.GlassHudState
import com.constellation.glass.hud.composables.AppStateHud
import timber.log.Timber

/**
 * On-eyepiece HUD render Activity (`glass` flavor).
 *
 *   - Transparent fullscreen window so unlit pixels stay AR-transparent.
 *   - KEEP_SCREEN_ON while alive (we want the panel awake during interactions).
 *   - Observes [GlassHudState.snapshot] and re-renders on every change.
 *   - Renderer is shared with phoneDebug (P1.6): all visuals live in
 *     [AppStateHud] in app/src/main/.../hud/composables/. This Activity is
 *     just the host (transparent fullscreen window).
 *
 * Lifecycle:
 *   - First click → Service starts this Activity (FLAG_ACTIVITY_NEW_TASK,
 *     singleTask). onCreate registers state collector.
 *   - System back / DOUBLE_CLICK on temple button → Activity finishes; Service
 *     stays alive. Next state transition restarts us.
 *
 * Pre-P1.6 this file built a handcrafted LinearLayout + TextView tree via
 * `buildView()` and `mkText()`, flattening styled runs via
 * `StyledRunsRenderer.flatten()` (lossy). Now it's pure Compose host.
 */
class GlassHudActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("GlassHudActivity · onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )

        setContent {
            val snap by GlassHudState.snapshot.collectAsState()
            AppStateHud(snap)
        }
    }

    override fun onDestroy() {
        Timber.i("GlassHudActivity · onDestroy")
        super.onDestroy()
    }
}
