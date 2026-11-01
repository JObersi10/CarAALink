package dev.caraalink

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
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
import dev.caraalink.settings.SettingsActivity

/**
 * Placeholder entry point. Real bring-up flow (MediaProjection capture, USB
 * transport) lands once the CarbitLink wire protocol is confirmed — see
 * claude/roadmap.md. The buttons below are all real and usable today;
 * everything else in the app is still a stub.
 */
class MainActivity : Activity() {

    companion object {
        private const val PREF_ONBOARDING_SHOWN = "onboarding_shown"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        maybeShowOnboarding()
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
