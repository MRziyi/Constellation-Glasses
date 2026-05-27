package com.constellation.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.unit.dp
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

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        results.forEach { (perm, granted) ->
            Timber.i("MainActivity · $perm granted=$granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity · onCreate")

        // Permissions up-front (idempotent if already granted).
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        if (needed.isNotEmpty()) {
            requestPermissions.launch(needed.toTypedArray())
        }
        // phoneDebug flavor needs SYSTEM_ALERT_WINDOW for the simulator overlay.
        if (!BuildConfig.IS_GLASS &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            promptForOverlayPermission()
        }

        // Start the foreground Service from THIS foreground Activity context,
        // not from ConstellationApp.onCreate (P1.8 finding 2026-05-29). The
        // Application context is "background" per Android 12+ BAL rules;
        // calling startForegroundService from there causes a silent FGS
        // denial (allowStartForeground=-1). MainActivity is in TOP/RESUMED
        // state at this moment (user-gesture launch) so the Service.start
        // gets the foreground-grant it needs to call startForeground
        // successfully. Idempotent — duplicate startForegroundService is fine.
        if (com.constellation.glass.auth.CookieStore.read(this) != null) {
            Timber.i("MainActivity · starting ConstellationService (foreground-context launch)")
            ConstellationService.start(this)
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
            onOpenSystemSettings = {
                // Open Android's top-level Settings app so the user can adjust
                // Wi-Fi / Bluetooth / etc. without leaving our app via the
                // Sprite launcher. FLAG_ACTIVITY_NEW_TASK is required because
                // we're launching a system app from a non-system context.
                try {
                    activity.startActivity(
                        Intent(Settings.ACTION_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (t: Throwable) {
                    Timber.w(t, "Failed to open Android system settings")
                }
            },
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
            is ShortcutsClient.Result.Ok -> {
                // Mirror the list into the local cache so HaloActionsProvider
                // (queried by Halo Ring at picker time) and HaloTriggerReceiver
                // (firing a shortcut) have an up-to-date offline view.
                ShortcutsLocalCache.write(ctx, r.value)
                ShortcutsCache.Ready(r.value)
            }
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
    var status by remember { mutableStateOf("Enter password or scan the QR code from your web console.") }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    if (scanning) {
        QrScanLoginOverlay(
            onCancel = { scanning = false },
            onScanned = { payload ->
                scanning = false
                busy = true
                status = "Pairing from QR…"
                activity.lifecycleScope.launch {
                    val parsed = parseQrPayload(payload)
                    if (parsed == null) {
                        busy = false
                        status = "QR didn't look right — try the AUTHORIZE flow."
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        EndpointStore.write(ctx, parsed.endpoint)
                        CookieStore.write(ctx, parsed.cookieName, parsed.cookieValue)
                    }
                    Timber.i("MainActivity · paired via QR; endpoint=${parsed.endpoint}")
                    onLoggedIn()
                }
            },
        )
        return
    }

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
        onScanQr = { scanning = true },
    )
}

// ────────────────────────────────────────────────────────────────────────────
// QR-pair flow
// ────────────────────────────────────────────────────────────────────────────

/** The shape inside the QR — emitted by the web console About page. */
private data class QrPayload(
    val endpoint: String,
    val cookieName: String,
    val cookieValue: String,
)

private fun parseQrPayload(raw: String): QrPayload? = try {
    val o = org.json.JSONObject(raw)
    val endpoint = o.optString("endpoint", "")
    val cookieName = o.optString("cookie_name", "")
    val cookieValue = o.optString("cookie_value", "")
    if (endpoint.isBlank() || cookieName.isBlank() || cookieValue.isBlank()) null
    else QrPayload(endpoint, cookieName, cookieValue)
} catch (t: Throwable) {
    Timber.w(t, "MainActivity · QR JSON parse failed")
    null
}

@Composable
private fun QrScanLoginOverlay(
    onCancel: () -> Unit,
    onScanned: (String) -> Unit,
) {
    BackHandler { onCancel() }
    Box(Modifier.fillMaxSize()) {
        com.constellation.glass.camera.QrScannerView(onDetected = onScanned)
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText(
                text = "Open web Console → About → Pair this device.\nPoint the camera at the QR.",
                style = TextStyle(fontSize = HudTheme.metaSize, color = HudTheme.fg),
            )
            com.constellation.glass.app.ui.Cta(text = "CANCEL", onClick = onCancel)
        }
    }
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

