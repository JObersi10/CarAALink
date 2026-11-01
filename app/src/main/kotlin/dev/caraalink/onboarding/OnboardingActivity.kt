package dev.caraalink.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.caraalink.accessibility.HeadUnitServerLauncher

/**
 * First-run (or revisit-anytime) checklist for everything the app needs to
 * actually survive being plugged into a car: accessibility service,
 * battery-optimization exemption, and (API 33+) notification permission.
 * Each item shows its live status and a button that jumps straight to the
 * fix — no persuasion copy, just the facts and the shortcut.
 */
class OnboardingActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()

        addItem(
            title = "Accessibility service",
            detail = "Required for one-click 'Start head unit server'",
            done = isAccessibilityServiceEnabled(),
            actionLabel = "Open settings",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

        addItem(
            title = "Battery optimization",
            detail = "Without this, Android may kill the USB link in the background",
            done = isIgnoringBatteryOptimizations(),
            actionLabel = "Request exemption",
        ) { requestIgnoreBatteryOptimizations() }

        if (Build.VERSION.SDK_INT >= 33) {
            addItem(
                title = "Notifications",
                detail = "Needed to show live connection status while driving",
                done = hasNotificationPermission(),
                actionLabel = "Open settings",
            ) {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            }
        }
    }

    private fun addItem(title: String, detail: String, done: Boolean, actionLabel: String, onFix: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(TextView(this).apply { text = (if (done) "✅ " else "❌ ") + title })
        row.addView(TextView(this).apply { text = detail })
        if (!done) {
            row.addView(Button(this).apply {
                text = actionLabel
                setOnClickListener { onFix() }
            })
        }
        root.addView(row)
    }

    private fun isAccessibilityServiceEnabled(): Boolean =
        HeadUnitServerLauncher.isAccessibilityServiceEnabled(this)

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
