package com.constellation.glass

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import timber.log.Timber

/**
 * One-shot launcher Activity. On first run, the wearer opens the app from
 * the launcher → we run the Rokid authorization handshake → persist the
 * token → start ConstellationService → close the Activity.
 *
 * On subsequent launches we skip the auth dance unless the token is missing
 * or rejected by the SDK. The service is the long-lived component; this
 * Activity is just plumbing for the initial pairing.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("MainActivity · onCreate")

        // Minimal visual: just a status TextView. Real UI not needed —
        // this Activity exists for the auth flow.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
        }
        val status = TextView(this).apply {
            textSize = 16f
            text = "Constellation\n\nChecking authorization…"
        }
        root.addView(status)
        setContentView(root)

        // ── DEV_HEADLESS: skip Rokid checks, kick the service in headless mode.
        if (BuildConfig.DEV_HEADLESS) {
            Timber.i("MainActivity · DEV_HEADLESS — starting service without Rokid auth")
            status.text = "Constellation · DEV mode\n\n" +
                "Headless: skipping Rokid auth. WSS + state machine only.\n\n" +
                "Tap to background — service stays alive."
            ConstellationService.start(this)
            return
        }

        // 1. Is the Rokid AI app installed at all?
        val sdkInstalled = try {
            AuthorizationHelper.INSTANCE.isRequiredRokidAppInstalled(this)
        } catch (t: Throwable) {
            Timber.w(t, "MainActivity · isRequiredRokidAppInstalled threw")
            false
        }
        if (!sdkInstalled) {
            status.text = "Constellation needs the Rokid AI app to render the HUD.\n\n" +
                "Install com.rokid.sprite.aiapp and reopen."
            return
        }

        // 2. Have we got a cached token?
        val existing = TokenStore.read(this)
        if (existing != null) {
            status.text = "Authorized. Starting Constellation service…"
            ConstellationService.start(this)
            finish()
            return
        }

        // 3. Kick off Rokid's authorization activity.
        status.text = "Requesting Rokid authorization…"
        AuthorizationHelper.INSTANCE.requestAuthorization(this, REQ_AUTH)
    }

    @Deprecated("Activity result API is fine for this single-shot flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUTH) return

        val parsed = try {
            // NB: the sample passes resultCode (not requestCode) here — they
            // mention the SDK's parser expects it. We mirror.
            AuthorizationHelper.INSTANCE.parseAuthorizationResult(resultCode, data)
        } catch (t: Throwable) {
            Timber.w(t, "MainActivity · parseAuthorizationResult threw")
            null
        }

        when (parsed) {
            is AuthResult.AuthSuccess -> {
                Timber.i("MainActivity · auth success")
                TokenStore.write(this, parsed.token)
                ConstellationService.start(this)
                finish()
            }
            is AuthResult.AuthFail -> {
                Timber.w("MainActivity · auth failed")
                findStatusView()?.text = "Authorization failed. Reopen the app to retry."
            }
            else -> {
                Timber.i("MainActivity · auth cancelled or null")
                findStatusView()?.text = "Authorization cancelled. Reopen the app to retry."
            }
        }
    }

    private fun findStatusView(): TextView? =
        (window.decorView.findViewWithTag<TextView>("status")) ?: run {
            val root = window.decorView.findViewById<LinearLayout>(android.R.id.content)
                ?: return null
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i)
                if (c is TextView) return c
            }
            null
        }

    companion object {
        private const val REQ_AUTH = 0x0c01
    }
}
