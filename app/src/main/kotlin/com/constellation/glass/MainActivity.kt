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
import com.constellation.glass.app.ui.ShortcutsListScreen
import com.constellation.glass.app.ui.ConnectionInfo
import com.constellation.glass.app.ui.CortexStatus
import com.constellation.glass.app.ui.LoginScreen
import com.constellation.glass.app.ui.MainScreen
import com.constellation.glass.app.ui.ScreenPadding
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.hud.HudTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The in-app settings UI host. Persistent Compose host rendering Login /
 * Main / Connect / About / Shortcuts via a simple
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

    // Set true to request the QR scanner open (from onCreate's intent or a later
    // onNewIntent). SettingsApp observes it and consumes it once.
    private val openScannerRequest = androidx.compose.runtime.mutableStateOf(false)

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
        // Start the FGS regardless of cookie state (was: only when a cookie
        // exists). With no/stale cookie the Service connects, gets 401/403, and
        // surfaces the AuthExpired HUD (LONG-press to re-pair) once MainActivity
        // is backgrounded — instead of staying invisible. Foreground-context
        // launch so BAL grants the startForeground. Idempotent.
        Timber.i("MainActivity · starting ConstellationService (foreground-context launch)")
        ConstellationService.start(this)

        openScannerRequest.value = intent?.getBooleanExtra(EXTRA_OPEN_SCANNER, false) == true
        setContent { SettingsApp(openScannerState = openScannerRequest) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Re-launched while already in the back stack (e.g. the AuthExpired HUD
        // long-press) → open the scanner DIRECTLY instead of landing on whatever
        // screen was last shown. onCreate doesn't re-run, so flip the state here.
        if (intent.getBooleanExtra(EXTRA_OPEN_SCANNER, false)) {
            openScannerRequest.value = true
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground.set(true)
        // R-14.b helper (also useful generally): if there's no active network
        // when the user enters MainActivity, jump straight to the Bluetooth
        // settings page so they can re-enable BT-PAN (the primary network mode
        // per docs/glass/NETWORK-ALTERNATIVES.md — Wi-Fi is the fallback).
        // Debounced via networkSettingsLaunchedAtMs so we don't loop-launch.
        maybeLaunchBluetoothSettingsIfOffline()
    }

    override fun onPause() {
        super.onPause()
        isForeground.set(false)
    }

    private var networkSettingsLaunchedAtMs: Long = 0L

    /**
     * If `ConnectivityManager.activeNetwork` is null at this moment, launch
     * the Android Bluetooth settings activity (BT-PAN is the primary network
     * per the省电模式 design). Debounced — won't re-fire within 30s of the
     * last launch so the user isn't trapped in a settings-flap loop.
     *
     * Launched from MainActivity (foreground) so BAL doesn't block it.
     * Service-side equivalent would have to handle BAL itself.
     */
    private fun maybeLaunchBluetoothSettingsIfOffline() {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager
                ?: return
            val active = cm.activeNetwork
            if (active != null) return  // online — nothing to do
            val nowMs = System.currentTimeMillis()
            if (nowMs - networkSettingsLaunchedAtMs < 30_000L) {
                Timber.i("MainActivity · offline but Bluetooth settings recently launched; skipping")
                return
            }
            Timber.i("MainActivity · offline (no active network) → launching Bluetooth settings")
            networkSettingsLaunchedAtMs = nowMs
            startActivity(
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (t: Throwable) {
            Timber.w(t, "MainActivity · offline-handler launch failed")
        }
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

        /** Intent extra: when true, open straight into the QR pairing scanner.
         *  Set by [ConstellationService.launchPairing] when the wearer
         *  LONG-presses the AuthExpired HUD. */
        const val EXTRA_OPEN_SCANNER = "open_scanner"
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Top-level Composable host
// ────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsApp(openScannerState: androidx.compose.runtime.MutableState<Boolean>) {
    val ctx = LocalContext.current
    val activity = ctx as MainActivity

    // Cookie presence drives the login gate. Re-read on every recomposition
    // triggered by cookieVersion bumps (set after a successful pairing).
    var cookieVersion by remember { mutableStateOf(0) }
    val cookie = remember(cookieVersion) { CookieStore.read(ctx) }
    val endpoint by EndpointStore.flow(ctx).collectAsState(initial = "")

    // QR pairing overlay — shared by the login gate, the Main "重新配对" row, AND
    // the AuthExpired HUD long-press. Writes endpoint + cookie from the QR, then
    // bounces the Service (reconfigure) so it reconnects with the fresh creds.
    var pairing by remember { mutableStateOf(false) }
    var pairStatus by remember {
        mutableStateOf("Scan the pairing QR from your web console (About / Pair).")
    }
    // onCreate / onNewIntent (e.g. AuthExpired HUD long-press) flips this →
    // open the scanner DIRECTLY, even if MainActivity was already running.
    androidx.compose.runtime.LaunchedEffect(openScannerState.value) {
        if (openScannerState.value) {
            pairing = true
            openScannerState.value = false
        }
    }
    if (pairing) {
        QrScanLoginOverlay(
            onCancel = { pairing = false },
            onScanned = { payload ->
                pairing = false
                pairStatus = "Pairing from QR…"
                activity.lifecycleScope.launch {
                    val parsed = parseQrPayload(payload)
                    if (parsed == null) {
                        pairStatus = "QR didn't look right — scan the one from About / Pair."
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        EndpointStore.write(ctx, parsed.endpoint)
                        CookieStore.write(ctx, parsed.cookieName, parsed.cookieValue)
                    }
                    Timber.i("MainActivity · paired via QR; endpoint=${parsed.endpoint}")
                    cookieVersion++
                    // reconfigure (stopService→start), not start: the Service may
                    // already be running in AuthExpired with a halted WSS — a plain
                    // start won't reconnect. Bouncing it re-reads the fresh creds.
                    ConstellationService.reconfigure(ctx)
                }
            },
        )
        return
    }

    if (cookie == null) {
        LoginScreen(
            endpoint = endpoint,
            status = pairStatus,
            onScanQr = { pairing = true },
        )
        return
    }

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
            onRescan = { pairing = true },
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
        NavRoute.About -> AboutScreen(
            appVersion = BuildConfig.VERSION_NAME,
            flavor = BuildConfig.PLATFORM,
            cortexConnected = status.connected,
        )

        NavRoute.Shortcuts -> ShortcutsListScreen(
            // Read-only view of the 3 fixed slots; editing is by voice
            // ("set shortcut 2 to …" → shortcut_config frame → ShortcutSlots).
            slots = ShortcutSlots.read(ctx),
            cortexConnected = status.connected,
        )
    }
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

