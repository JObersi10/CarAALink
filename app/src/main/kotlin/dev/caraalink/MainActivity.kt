package dev.caraalink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import dev.caraalink.accessibility.HeadUnitServerLauncher
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(button("Start head unit server") { HeadUnitServerLauncher.start(this) })
        root.addView(button("Setup checklist") { open(OnboardingActivity::class.java) })
        root.addView(button("Connection diagnostics") { open(ConnectionDiagnosticsActivity::class.java) })
        root.addView(button("Developer tools") { open(DeveloperToolsActivity::class.java) })
        root.addView(button("Settings") { open(SettingsActivity::class.java) })

        setContentView(root)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }
}
