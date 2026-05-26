package com.constellation.glass.phonedebug

import com.constellation.glass.hud.HudSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide [HudSnapshot] holder for the **phoneDebug** flavor. Parallel
 * to [com.constellation.glass.glass.GlassHudState] but lives in the phoneDebug
 * sourceset so it doesn't ship in the glass APK.
 *
 * **Why a per-flavor holder?** Each flavor's "host" surface (Activity for
 * glass; overlay for phoneDebug) needs to observe the snapshot, and the
 * snapshot lifetime is tied to that flavor's Service. Sharing one global
 * holder across flavors would work but is misleading (only one runs at a time
 * per APK). Keeping them parallel keeps the C-40 invariant clean.
 *
 * The renderer ([com.constellation.glass.hud.composables.AppStateHud]) and
 * the data shape ([HudSnapshot]) are both in `main/` — only the StateFlow
 * mailbox is flavor-specific.
 */
object PhoneDebugHudState {

    private val _snapshot = MutableStateFlow(HudSnapshot())
    val snapshot: StateFlow<HudSnapshot> = _snapshot.asStateFlow()

    fun update(transform: HudSnapshot.() -> HudSnapshot) {
        _snapshot.value = _snapshot.value.transform()
    }
}
