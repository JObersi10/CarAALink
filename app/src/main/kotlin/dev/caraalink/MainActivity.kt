package dev.caraalink

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.widget.Toast
import dev.caraalink.video.CarHomePresentation
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.caraalink.accessibility.HeadUnitServerLauncher
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.diagnostics.ConnectionDiagnosticsActivity
import dev.caraalink.diagnostics.DeveloperToolsActivity
import dev.caraalink.onboarding.OnboardingActivity
import dev.caraalink.service.UsbLinkForegroundService
import dev.caraalink.settings.SettingsActivity

/**
 * Placeholder entry point. Real bring-up flow (MediaProjection capture, USB
 * transport) lands once the CarbitLink wire protocol is confirmed — see
 * claude/roadmap.md. The buttons below are all real and usable today;
 * everything else in the app is still a stub.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PREF_ONBOARDING_SHOWN = "onboarding_shown"
        private const val REQUEST_BACKGROUND = 7
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate: launch intent action=${intent?.action} extras=${intent?.extras}")
        render()
        maybeShowOnboarding()
        maybeAutoStartOnAttach(intent)
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // The car asks the phone for GPS (its own nav/clock), plus weather on the home screen.
            requestPermissions(arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ), 8)
        }
    }

    /**
     * Auto-starts the ADB handshake probe, but ONLY when this Activity was
     * actually launched by the USB_ACCESSORY_ATTACHED intent -- i.e. only
     * when the user picked CarAALink in Android's resolver chooser. This is
     * the fix for the previous attempt (claude/roadmap.md): wiring this to
     * UsbAttachReceiver instead fired on EVERY attach regardless of which
     * app was chosen, racing against CarbitLink even when CarbitLink was
     * picked. MainActivity only ever gets this intent when CarAALink is the
     * one chosen, so there's no race with CarbitLink anymore.
     */
    private fun maybeAutoStartOnAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return
        @Suppress("DEPRECATION")
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY) ?: return
        Log.i(TAG, "maybeAutoStartOnAttach: launched via attach intent, starting car session")
        val serviceIntent = Intent(this, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_SESSION)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        startForegroundService(serviceIntent)
    }

    @Deprecated("Plain Activity; result API not used")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_BACKGROUND || resultCode != RESULT_OK || uri == null) return
        Thread {
            // Downscale so a 50 MP photo doesn't blow up memory on every car connect.
            val ok = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                } ?: error("decode failed")
                CarHomePresentation.backgroundFile(this).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (ok) "Car background set (shows on next connect)" else "Couldn't read that image", Toast.LENGTH_SHORT).show()
                render()
            }
        }.start()
    }

    // When the app is already running, picking it in the USB chooser delivers
    // the attach intent here instead of onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent: action=${intent.action}")
        maybeAutoStartOnAttach(intent)
    }

    private fun maybeShowOnboarding() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ONBOARDING_SHOWN, false)) return
        prefs.edit().putBoolean(PREF_ONBOARDING_SHOWN, true).apply()
        startActivity(Intent(this, OnboardingActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        render() // keeps the status line fresh without a dedicated observer here
    }

    private fun render() {
        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "CarAALink"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            setTextColor(Color.parseColor("#F2F5F7"))
        })

        root.addView(TextView(this).apply {
            text = "Status: ${ConnectionStateHolder.snapshot.phase}"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(Color.parseColor("#9AA7B2"))
            setPadding(0, dp(4), 0, dp(24))
        })

        primaryButton("Start head unit server") { HeadUnitServerLauncher.start(this) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Setup checklist") { open(OnboardingActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Connection diagnostics") { open(ConnectionDiagnosticsActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Developer tools") { open(DeveloperToolsActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        outlineButton("Settings") { open(SettingsActivity::class.java) }.also { root.addView(it) }
        spacer(root)
        val bgSet = CarHomePresentation.backgroundFile(this).exists()
        outlineButton(if (bgSet) "Car background: custom (hold to reset)" else "Car background: pick a photo") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),
                REQUEST_BACKGROUND,
            )
        }.also { button ->
            button.setOnLongClickListener {
                CarHomePresentation.backgroundFile(this).delete()
                render()
                true
            }
            root.addView(button)
        }

        setContentView(root)
    }

    private fun primaryButton(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cornerRadius = dp(12)
        setBackgroundColor(Color.parseColor("#36C9C6"))
        setTextColor(Color.parseColor("#0A0F12"))
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cornerRadius = dp(12)
        setTextColor(Color.parseColor("#F2F5F7"))
        strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#36C9C6"))
    }

    private fun spacer(parent: LinearLayout) {
        parent.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12))
        })
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }
}
