package dev.caraalink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import dev.caraalink.accessibility.HeadUnitServerLauncher
import dev.caraalink.settings.SettingsActivity

/**
 * Placeholder entry point. Real bring-up flow (MediaProjection capture, USB
 * transport) lands once the CarbitLink wire protocol is confirmed — see
 * claude/roadmap.md. The one-click "start head unit server" button and the
 * settings shortcut are real and usable today; everything else here is a stub.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        root.addView(
            Button(this).apply {
                text = "Start head unit server"
                setOnClickListener { HeadUnitServerLauncher.start(this@MainActivity) }
            }
        )

        root.addView(
            Button(this).apply {
                text = "Settings"
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
            }
        )

        setContentView(root)
    }
}
