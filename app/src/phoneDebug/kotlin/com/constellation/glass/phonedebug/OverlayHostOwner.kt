package com.constellation.glass.phonedebug

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * Minimal `LifecycleOwner` + `ViewModelStoreOwner` + `SavedStateRegistryOwner`
 * impl, used to attach to a [androidx.compose.ui.platform.ComposeView] that
 * lives inside a SYSTEM_ALERT_WINDOW overlay (not an Activity).
 *
 * **Why this exists**: ComposeView's setContent path looks up these three
 * owners from the View's parent hierarchy via the AndroidX `ViewTree*Owner`
 * static helpers. An Activity / Fragment provides them automatically; a
 * WindowManager overlay does not, so without manual wiring Compose throws
 * "ViewTreeLifecycleOwner not found" at runtime.
 *
 * Lifecycle is created in RESUMED state and stays there until [destroy] is
 * called (overlay teardown). The single ViewModelStore is cleared on destroy.
 *
 * This is local to the phoneDebug flavor — the glass flavor's Compose host
 * is a real Activity which already provides all three owners.
 */
class OverlayHostOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.INITIALIZED
    }
    override val lifecycle: Lifecycle = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore = store

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateController.savedStateRegistry

    init {
        // Required handshake before Compose attaches.
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /** Call from PhoneDebugHudSurface.destroy() so Compose tears down cleanly. */
    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
