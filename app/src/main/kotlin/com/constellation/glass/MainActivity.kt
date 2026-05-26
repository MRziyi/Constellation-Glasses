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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One-shot launcher Activity for first-time setup.
 *
 *   1. Request RECORD_AUDIO permission (needed for AudioCapture).
 *   2. If cookie missing → show password form → POST `/api/auth/login` →
 *      persist cookie.
 *   3. Start [ConstellationService] and finish.
 *
 * v2.1: dropped the Rokid `AuthorizationHelper` token flow — bare-metal
 * doesn't need a Rokid AI App authorization. Cookie + RECORD_AUDIO is the
 * full setup.
 *
 * Steady-state runtime is entirely in [ConstellationService]; this Activity
 * should rarely be opened after first launch.
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.i("MainActivity · RECORD_AUDIO granted=$granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity · onCreate")
        setContentView(buildUi())

        // Ask for mic up-front (the AudioCapture needs it).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        // phoneDebug flavor: also nudge user to grant SYSTEM_ALERT_WINDOW so
        // the floating debug HUD overlay can appear. Glass flavor doesn't need
        // this (it uses an Activity).
        if (!BuildConfig.IS_GLASS &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(this)
        ) {
            promptForOverlayPermission()
        }

        val cookie = CookieStore.read(this)
        if (cookie != null) {
            status.text = "Constellation · authorized\n\nStarting service…"
            hideLogin()
            ConstellationService.start(this)
            finish()
            return
        }

        status.text = "Enter your Cortex password to authorize this device."
        showLogin()
        loginButton.setOnClickListener { performLogin() }
    }

    /** phoneDebug-only — open the system page so the user can grant overlay. */
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
                    status.text = "Authorized. Starting service…"
                    ConstellationService.start(this@MainActivity)
                    finish()
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

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 84, 56, 56)
            gravity = Gravity.TOP
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
}
