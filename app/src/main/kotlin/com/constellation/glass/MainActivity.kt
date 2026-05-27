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
import com.constellation.glass.app.ui.AboutScreen
import com.constellation.glass.app.ui.AppChrome
import com.constellation.glass.app.ui.ConnectScreen
import com.constellation.glass.app.ui.ShortcutEditorScreen
import com.constellation.glass.app.ui.ShortcutsListScreen
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

    // Shortcuts state — fetched on Shortcuts screen entry, kept across edits
    // so list refresh after save doesn't show a "Loading…" flicker.
    val shortcutsState = remember { mutableStateOf<ShortcutsCache>(ShortcutsCache.Idle) }

    when (val top = navStack.lastOrNull()) {
        null -> MainScreen(
            status = status,
            shortcutCount = 0,
            onNavigate = { navStack = navStack + it },
        )
        NavRoute.Connect -> {
            var toast by remember { mutableStateOf<String?>(null) }
            ConnectScreen(
                info = ConnectionInfo(
                    endpoint = endpoint,
                    connected = status.connected,
                    cookiePersisted = true,
                    lastInvokeAgo = status.lastInvokeAgo,
                    toast = toast,
                ),
                onNavigate = { navStack = navStack + it },
                onTestConnection = {
                    toast = "pinging…"
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            CortexPingClient.fetch(ctx, endpoint)
                        }
                        toast = with(CortexPingClient) { result.toUserMessage() }
                        // Auto-clear after 4s so the toast doesn't linger forever
                        delay(4_000L)
                        toast = null
                    }
                },
            )
        }
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
        NavRoute.About -> AboutScreen(
            appVersion = BuildConfig.VERSION_NAME,
            flavor = BuildConfig.PLATFORM,
            cortexConnected = status.connected,
        )

        NavRoute.Shortcuts -> {
            LaunchedEffect(endpoint, navStack.size) {
                refreshShortcuts(ctx, endpoint, shortcutsState)
            }
            val s = shortcutsState.value
            ShortcutsListScreen(
                shortcuts = (s as? ShortcutsCache.Ready)?.list ?: emptyList(),
                loading = s is ShortcutsCache.Loading || s is ShortcutsCache.Idle,
                error = (s as? ShortcutsCache.Error)?.msg,
                cortexConnected = status.connected,
                onPick = { sc -> navStack = navStack + NavRoute.ShortcutEdit(sc.id) },
                onNew = { navStack = navStack + NavRoute.ShortcutEdit("") },
            )
        }

        is NavRoute.ShortcutEdit -> {
            val cached = (shortcutsState.value as? ShortcutsCache.Ready)?.list.orEmpty()
            val existing = cached.firstOrNull { it.id == top.id }
            var busy by remember(top.id) { mutableStateOf(false) }
            ShortcutEditorScreen(
                existing = existing,
                cortexConnected = status.connected,
                busy = busy,
                onCancel = pop,
                onSave = { name, prompt, photo ->
                    busy = true
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            if (existing != null) {
                                ShortcutsClient.update(ctx, endpoint, existing.id, name, prompt, photo)
                            } else {
                                ShortcutsClient.create(ctx, endpoint,
                                    id = slugify(name), name = name, prompt = prompt, photo = photo)
                            }
                        }
                        busy = false
                        if (result is ShortcutsClient.Result.Ok) {
                            refreshShortcuts(ctx, endpoint, shortcutsState)
                            pop()
                        } else {
                            Timber.w("shortcut save failed: $result")
                        }
                    }
                },
                onDelete = {
                    val id = existing?.id ?: return@ShortcutEditorScreen
                    busy = true
                    activity.lifecycleScope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ShortcutsClient.delete(ctx, endpoint, id)
                        }
                        busy = false
                        if (result is ShortcutsClient.Result.Ok) {
                            refreshShortcuts(ctx, endpoint, shortcutsState)
                            pop()
                        } else {
                            Timber.w("shortcut delete failed: $result")
                        }
                    }
                },
            )
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Shortcuts state machine
// ────────────────────────────────────────────────────────────────────────────

private sealed interface ShortcutsCache {
    object Idle : ShortcutsCache
    object Loading : ShortcutsCache
    data class Ready(val list: List<ShortcutsClient.Shortcut>) : ShortcutsCache
    data class Error(val msg: String) : ShortcutsCache
}

private suspend fun refreshShortcuts(
    ctx: android.content.Context,
    endpoint: String,
    state: androidx.compose.runtime.MutableState<ShortcutsCache>,
) {
    state.value = ShortcutsCache.Loading
    state.value = withContext(Dispatchers.IO) {
        when (val r = ShortcutsClient.list(ctx, endpoint)) {
            is ShortcutsClient.Result.Ok -> ShortcutsCache.Ready(r.value)
            is ShortcutsClient.Result.HttpError -> ShortcutsCache.Error("HTTP ${r.code}")
            is ShortcutsClient.Result.NetworkError -> ShortcutsCache.Error(r.msg)
        }
    }
}

/** Derive a kebab-case id from a free-form name. */
private fun slugify(name: String): String {
    val cleaned = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return if (cleaned.isEmpty()) "shortcut-${System.currentTimeMillis()}" else cleaned.take(48)
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
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(CortexStatus(endpoint = endpoint)) }
    LaunchedEffect(endpoint) {
        while (true) {
            status = withContext(Dispatchers.IO) {
                CortexHealthClient.fetch(ctx, endpoint).copy(endpoint = endpoint)
            }
            delay(5_000L)
        }
    }
    return status
}

