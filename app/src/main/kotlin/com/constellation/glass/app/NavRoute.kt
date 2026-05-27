package com.constellation.glass.app

/**
 * Typed nav stack for the in-app settings UI.
 *
 * Modeled after Halo Ring's `SubScreen` sealed hierarchy
 * (`Halo-Ring/app-project/app/src/main/kotlin/com/halo/ring/ui/Navigation.kt`).
 * Stack lives in `MainActivity` as `mutableStateOf(List<NavRoute>)`. Empty
 * stack = Main is the root. Pushing drills in; popping returns to parent.
 *
 * Physical-key bridge (per IN-APP-UI-DESIGN.md §4):
 *   - DOUBLE_CLICK on root (`navStack.isEmpty()`) = `moveTaskToBack()` → launcher
 *   - DOUBLE_CLICK on any deeper screen = pop one
 *
 * Login is NOT in this stack — it's gated at the Activity level by
 * `CookieStore` presence. If no cookie, the Login screen replaces Main; once
 * login succeeds, the user moves into the normal navigation flow.
 */
sealed interface NavRoute {
    /** Connection status detail + endpoint editor + TEST CONNECTION. */
    data object Connect : NavRoute

    /** Endpoint URL edit field (one-screen modal under Connect). */
    data object EditEndpoint : NavRoute

    /** Static info. */
    data object About : NavRoute

    /** Shortcuts list (P-app.D — placeholder for now). */
    data object Shortcuts : NavRoute

    /** Shortcut editor for a specific shortcut id (P-app.D). */
    data class ShortcutEdit(val id: String) : NavRoute
}
