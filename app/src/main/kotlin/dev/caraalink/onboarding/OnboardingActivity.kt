package dev.caraalink.onboarding

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
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
        val pad = dp(24)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        setContentView(
            ScrollView(this).apply {
                setBackgroundColor(Color.parseColor("#0E1116"))
                addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        root.removeAllViews()

        root.addView(TextView(this).apply {
            text = "Setup checklist"
            setTextColor(Color.parseColor("#F2F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setPadding(0, 0, 0, dp(20))
        })

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
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#1B2129"))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        card.addView(TextView(this).apply {
            text = (if (done) "✓  " else "○  ") + title
            setTextColor(if (done) Color.parseColor("#36C9C6") else Color.parseColor("#F2F5F7"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        card.addView(TextView(this).apply {
            text = detail
            setTextColor(Color.parseColor("#9AA7B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, 0)
        })
        if (!done) {
            card.addView(MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = actionLabel
                isAllCaps = false
                textSize = 15f
                setOnClickListener { onFix() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                    topMargin = dp(12)
                }
                cornerRadius = dp(14)
                setTextColor(Color.parseColor("#F2F5F7"))
                strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#36C9C6"))
            })
        }
        root.addView(card)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun isAccessibilityServiceEnabled(): Boolean =
        HeadUnitServerLauncher.isAccessibilityServiceEnabled(this)

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(direct)
        } catch (e: Exception) {
            // Some OEM builds (and ones missing the exemption permission —
            // see AndroidManifest.xml) refuse the direct per-app dialog.
            // Fall back to the generic list so the user can still find and
            // exempt the app manually.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
