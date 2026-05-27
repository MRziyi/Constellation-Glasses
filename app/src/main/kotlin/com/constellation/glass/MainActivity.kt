package com.constellation.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.constellation.glass.app.EndpointStore
import com.constellation.glass.app.NavRoute
import com.constellation.glass.app.ui.AppChrome
import com.constellation.glass.app.ui.ConnectScreen
import com.constellation.glass.app.ui.ConnectionInfo
import com.constellation.glass.app.ui.CortexStatus
import com.constellation.glass.app.ui.EditEndpointScreen
import com.constellation.glass.app.ui.LoginScreen
import com.constellation.glass.app.ui.MainScreen
import com.constellation.glass.app.ui.ScreenPadding
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.auth.CortexAuth
import com.constellation.glass.hud.HudTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The in-app settings UI host. Persistent Compose host rendering Login /
 * Main / Connect / EditEndpoint / About / Shortcuts via a simple
 * `List<NavRoute>` stack.
 *
 * P-app.A (2026-05-26) — rewrite from the pre-P-app.A one-shot launcher
 * (which built a hand-coded `LinearLayout` + login form then `finish()`'d).
 * Activity now stays alive for the duration of the user's settings session
 * and exposes [isForeground] so `GlassHudSurface` knows not to fight for
 * the panel while the user is configuring.
 *
 * Navigation model (no androidx.navigation dep — simple sealed-class stack):
 *   - [NavRoute] sealed hierarchy in `app/NavRoute.kt`
 *   - State held in [SettingsApp] as `List<NavRoute>` (LIFO)
 *   - BackHandler pops; empty stack → `moveTaskToBack(true)` exits to launcher
 *
 * Login gate: rendered above the stack when [CookieStore.read] is null. Once
 * login succeeds, cookie persists and Login is never shown again (per user
 * direction 2026-05-26: no logout).
 */
class MainActivity : ComponentActivity() {

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> Timber.i("MainActivity · RECORD_AUDIO granted=$granted") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity · onCreate")

        // Permissions up-front (idempotent if already granted).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        // phoneDebug flavor needs SYSTEM_ALERT_WINDOW for the simulator overlay.
        if (!BuildConfig.IS_GLASS &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            promptForOverlayPermission()
        }

        setContent { SettingsApp() }
    }

    override fun onResume() {
        super.onResume()
        isForeground.set(true)
    }

    override fun onPause() {
        super.onPause()
        isForeground.set(false)
    }

    private fun promptForOverlayPermission() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        } catch (t: Throwable) {
            Timber.w(t, "MainActivity · cannot open overlay-permission settings")
        }
    }

    companion object {
        /**
         * True while MainActivity is in the foreground. Read by
         * `GlassHudSurface.bringActivityToFront()` to skip launching the HUD
         * Activity (which would otherwise steal the panel from settings UI).
         * Service still updates the snapshot — when the user exits MainActivity,
         * the next inbound state transition naturally brings up the HUD.
         */
        val isForeground = AtomicBoolean(false)
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Top-level Composable host
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsApp() {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity

    // Cookie presence drives the login gate. Re-read on every recomposition
    // triggered by cookieVersion bumps (login success branch).
    var cookieVersion by remember { mutableStateOf(0) }
    val cookie = remember(cookieVersion) { CookieStore.read(ctx) }

    if (cookie == null) {
        LoginGate(onLoggedIn = {
            cookieVersion++
            ConstellationService.start(ctx)
        })
        return
    }

    val endpoint by EndpointStore.flow(ctx).collectAsState(initial = BuildConfig.WSS_URL)

    var navStack by remember { mutableStateOf<List<NavRoute>>(emptyList()) }
    val pop: () -> Unit = {
        if (navStack.isEmpty()) {
            activity.moveTaskToBack(true)
        } else {
            navStack = navStack.dropLast(1)
        }
    }
    BackHandler(enabled = true) { pop() }

    // Live cortex health — polled while MainActivity is foreground.
    val status = useCortexHealth(endpoint = endpoint)

    when (val top = navStack.lastOrNull()) {
        null -> MainScreen(
            status = status,
            shortcutCount = 0,
            onNavigate = { navStack = navStack + it },
        )
        NavRoute.Connect -> ConnectScreen(
            info = ConnectionInfo(
                endpoint = endpoint,
                connected = status.connected,
                cookiePersisted = true,
                lastInvokeAgo = status.lastInvokeAgo,
            ),
            onNavigate = { navStack = navStack + it },
            onTestConnection = { /* Phase B */ },
        )
        NavRoute.EditEndpoint -> EditEndpointScreen(
            currentEndpoint = endpoint,
            cortexConnected = status.connected,
            onCancel = pop,
            onSaved = { newUrl ->
                activity.lifecycleScope.launch {
                    EndpointStore.write(ctx, newUrl)
                    ConstellationService.reconfigure(ctx)
                    pop()
                }
            },
        )
        NavRoute.About -> AboutPlaceholder(onBack = pop)
        NavRoute.Shortcuts -> ShortcutsPlaceholder(onBack = pop)
        is NavRoute.ShortcutEdit -> ShortcutsPlaceholder(onBack = pop)
    }
}

@Composable
private fun LoginGate(onLoggedIn: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity
    val endpoint by EndpointStore.flow(ctx).collectAsState(initial = BuildConfig.WSS_URL)
    var status by remember { mutableStateOf("Enter your Cortex password to authorize this device.") }
    var busy by remember { mutableStateOf(false) }

    LoginScreen(
        endpoint = endpoint,
        status = status,
        busy = busy,
        onSubmit = { pw ->
            busy = true
            status = "Authorizing with Cortex…"
            activity.lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { CortexAuth.login(pw) }
                busy = false
                when (result) {
                    is CortexAuth.Result.Success -> {
                        CookieStore.write(ctx, result.cookie.name, result.cookie.value)
                        Timber.i("MainActivity · login OK; cookie ${result.cookie.name} stored")
                        onLoggedIn()
                    }
                    is CortexAuth.Result.BadPassword ->
                        status = "Bad password (HTTP ${result.httpCode}). Try again."
                    is CortexAuth.Result.Throttled ->
                        status = result.msg
                    is CortexAuth.Result.NetworkError ->
                        status = "Can't reach Cortex edge: ${result.msg}"
                }
            }
        },
    )
}

@Composable
private fun useCortexHealth(endpoint: String): CortexStatus {
    var status by remember { mutableStateOf(CortexStatus(endpoint = endpoint)) }
    LaunchedEffect(endpoint) {
        while (true) {
            status = withContext(Dispatchers.IO) {
                CortexHealthClient.fetch(endpoint).copy(endpoint = endpoint)
            }
            delay(5_000L)
        }
    }
    return status
}

// ────────────────────────────────────────────────────────────────────────────
// Placeholder screens for nav entries not yet implemented in P-app.A
// (About lands in Phase C; Shortcuts in Phase D).
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun AboutPlaceholder(onBack: () -> Unit) {
    BackHandler { onBack() }
    AppChrome(title = "ABOUT", cortexConnected = true) {
        BasicText(
            text = "About — coming in Phase C",
            style = TextStyle(fontSize = HudTheme.bodySize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}

@Composable
private fun ShortcutsPlaceholder(onBack: () -> Unit) {
    BackHandler { onBack() }
    AppChrome(title = "SHORTCUTS", cortexConnected = true) {
        BasicText(
            text = "Shortcuts — coming in Phase D\n\n" +
                "Will read/write from your Cortex Twin\n" +
                "(~/constellation/twin/skills/shortcuts.md)\n" +
                "and expose actions via HaloActionsProvider\n" +
                "for Halo Ring gesture binding.",
            style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fgDim),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    }
}
