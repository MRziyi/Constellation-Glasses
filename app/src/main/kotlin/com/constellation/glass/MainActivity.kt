package com.constellation.glass

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.constellation.glass.auth.CookieStore
import com.constellation.glass.auth.CortexAuth
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Launcher Activity. Two responsibilities:
 *   1. Get a Cortex edge cookie via password login (always required).
 *   2. Get a Rokid CXR-L token via AuthorizationHelper (production only;
 *      skipped in DEV_HEADLESS).
 *
 * Both succeed → start ConstellationService → finish.
 * Either missing → leave the Activity up with a status message.
 *
 * The Activity is only shown when the user opens the app icon — it's not
 * needed for the steady-state runtime. The FGS handles everything else.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button

    /** RECORD_AUDIO request — we need it for AudioPipeline (Phase 3b.4). If
     *  denied we still start the service, but AudioPipeline will refuse to
     *  capture and log a warning. */
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.i("MainActivity · RECORD_AUDIO granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity · onCreate")
        setContentView(buildUi())

        // Ask for mic up-front (it's needed for streaming PCM). The system
        // dialog defers the rest of onCreate's logic until the user answers.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // DEV_HEADLESS only: the on-phone debug HUD needs SYSTEM_ALERT_WINDOW.
        // We don't block on it — service still works without the overlay (logs
        // only) — but offer a one-tap link to the system settings.
        if (BuildConfig.DEV_HEADLESS &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            promptForOverlayPermission()
        }

        // If we already have a cookie + (Rokid token OR DEV_HEADLESS), the
        // service can run; skip showing the login form.
        val cookie = CookieStore.read(this)
        val rokidReady = BuildConfig.DEV_HEADLESS || TokenStore.read(this) != null
        if (cookie != null && rokidReady) {
            status.text = "Constellation · already authorized\n\nStarting service…"
            hideLogin()
            ConstellationService.start(this)
            finish()
            return
        }

        // Cookie path: show password input.
        if (cookie == null) {
            status.text = "Enter your Cortex password to authorize this device."
            showLogin()
            loginButton.setOnClickListener { performLogin() }
            return
        }

        // Cookie ok but no Rokid token; jump to Rokid auth.
        startRokidAuth()
    }

    @Deprecated("Activity Result API is overkill for this one-shot flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ROKID_AUTH) return
        val parsed = try {
            AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data)
        } catch (t: Throwable) {
            Timber.w(t, "MainActivity · parseAuthorizationResult threw"); null
        }
        when (parsed) {
            is AuthResult.AuthSuccess -> {
                TokenStore.write(this, parsed.token)
                ConstellationService.start(this)
                finish()
            }
            is AuthResult.AuthFail -> status.text = "Rokid authorization failed. Reopen the app to retry."
            else -> status.text = "Rokid authorization cancelled. Reopen the app to retry."
        }
    }

    /** DEV_HEADLESS only — open the system page so the user can grant the
     *  overlay permission, then return to the app. Non-blocking; if the user
     *  declines, the HUD just stays log-only. */
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

    // ── login flow ─────────────────────────────────────────────────────

    private fun performLogin() {
        val pw = passwordInput.text.toString()
        if (pw.isBlank()) {
            status.text = "Password is required."
            return
        }
        loginButton.isEnabled = false
        status.text = "Authorizing with Cortex…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { CortexAuth.login(pw) }
            when (result) {
                is CortexAuth.Result.Success -> {
                    CookieStore.write(this@MainActivity, result.cookie.name, result.cookie.value)
                    Timber.i("MainActivity · login OK; cookie ${result.cookie.name} stored")
                    if (BuildConfig.DEV_HEADLESS) {
                        status.text = "Authorized. Starting service (DEV headless)…"
                        ConstellationService.start(this@MainActivity)
                        finish()
                    } else {
                        // Move on to Rokid auth in the production path.
                        startRokidAuth()
                    }
                }
                is CortexAuth.Result.BadPassword -> {
                    status.text = "Bad password (HTTP ${result.httpCode}). Try again."
                    loginButton.isEnabled = true
                    passwordInput.text.clear()
                }
                is CortexAuth.Result.Throttled -> {
                    status.text = result.msg
                    loginButton.isEnabled = true
                }
                is CortexAuth.Result.NetworkError -> {
                    status.text = "Can't reach Cortex edge: ${result.msg}\n\n" +
                        "Endpoint: ${CortexAuth.edgeBaseUrl()}/api/auth/login"
                    loginButton.isEnabled = true
                }
            }
        }
    }

    private fun startRokidAuth() {
        val sdkInstalled = try {
            AuthorizationHelper.INSTANCE.isRequiredRokidAppInstalled(this)
        } catch (t: Throwable) { false }
        if (!sdkInstalled) {
            status.text = "Cortex authorized ✓\n\n" +
                "Glass HUD needs the Rokid AI app (com.rokid.sprite.aiapp).\n" +
                "Install it and reopen, or rebuild with DEV_HEADLESS=true."
            hideLogin()
            return
        }
        status.text = "Cortex authorized ✓\n\nRequesting Rokid HUD authorization…"
        hideLogin()
        AuthorizationHelper.INSTANCE.requestAuthorization(this, REQ_ROKID_AUTH)
    }

    // ── UI ─────────────────────────────────────────────────────────────

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 84, 56, 56)
            gravity = Gravity.TOP
            // Theme.Constellation has a black windowBackground; ensure our
            // text widgets paint visibly on it.
            setBackgroundColor(Color.parseColor("#050A06"))
        }
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.parseColor("#B7FFC7"))
            text = "Constellation\n\nLoading…"
        }
        passwordInput = EditText(this).apply {
            hint = "Cortex password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 17f
            setSingleLine(true)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#5EE08C"))
            visibility = TextView.GONE
        }
        loginButton = Button(this).apply {
            text = "AUTHORIZE"
            setTextColor(Color.parseColor("#050A06"))
            setBackgroundColor(Color.parseColor("#5EE08C"))
            visibility = Button.GONE
        }
        root.addView(status)
        // 24dp gap
        root.addView(android.view.View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 56))
        root.addView(passwordInput)
        root.addView(android.view.View(this), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24))
        root.addView(loginButton)
        return root
    }

    private fun showLogin() {
        passwordInput.visibility = TextView.VISIBLE
        loginButton.visibility = Button.VISIBLE
    }

    private fun hideLogin() {
        passwordInput.visibility = TextView.GONE
        loginButton.visibility = Button.GONE
    }

    companion object {
        private const val REQ_ROKID_AUTH = 0x0c01
    }
}
