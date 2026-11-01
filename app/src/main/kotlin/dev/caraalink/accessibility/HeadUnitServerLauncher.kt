package dev.caraalink.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.Settings
import androidx.preference.PreferenceManager
import android.widget.Toast

/**
 * One-click entry point: launches Android Auto's Settings screen and arms
 * the accessibility service to tap its overflow menu's "Start head unit
 * server" item, so the user doesn't have to do that part by hand every time.
 *
 * The settings-intent fallback chain and the "tap Version 10x is a one-time
 * manual step, not something to automate" design are both CONFIRMED by
 * decompiling PhoneAuto-beta-2027-01-19.apk (jadx, this session — see
 * claude/roadmap.md): its HeadUnitServerGuide.a() tries these exact intents
 * in this exact order. Our first implementation only tried the generic
 * launcher intent (step 3 below), which is why it landed on the wrong
 * screen on real hardware instead of AA's actual Settings.
 */
object HeadUnitServerLauncher {

    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val PREF_VERSION_TAP_GUIDED = "head_unit_server_version_guided"

    fun start(context: Context) {
        if (!isAccessibilityServiceEnabled(context)) {
            promptEnableAccessibilityService(context)
            return
        }

        maybeShowOneTimeVersionTapGuide(context)

        // Arm BEFORE launching -- the service needs to be listening for the
        // very first window-state-changed event once AA's Settings appears.
        HeadUnitServerAccessibilityService.arm()

        if (!launchAndroidAutoSettings(context)) {
            HeadUnitServerAccessibilityService.disarm()
            Toast.makeText(
                context,
                "Couldn't open Android Auto Settings automatically — opening its app info instead",
                Toast.LENGTH_LONG,
            ).show()
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$ANDROID_AUTO_PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * CONFIRMED order and content from HeadUnitServerGuide.a() in the
     * decompiled reference app: a dedicated settings deep-link action first,
     * then an explicit settings activity component, then the generic
     * launcher intent (unreliable for AA -- see the old comment this
     * replaced), then app-info as the final fallback.
     */
    private fun launchAndroidAutoSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent("com.google.android.projection.gearhead.SETTINGS").setPackage(ANDROID_AUTO_PACKAGE),
            Intent().setClassName(
                ANDROID_AUTO_PACKAGE,
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity",
            ),
            context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$ANDROID_AUTO_PACKAGE")),
        )
        for (intent in candidates) {
            if (intent == null) continue
            val result = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (result.isSuccess) return true
        }
        return false
    }

    private fun maybeShowOneTimeVersionTapGuide(context: Context) {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_VERSION_TAP_GUIDED, false)) return
        prefs.edit().putBoolean(PREF_VERSION_TAP_GUIDED, true).apply()
        Toast.makeText(
            context,
            "First time only: in Android Auto's Settings, scroll to the bottom and tap " +
                "\"Version\" 10 times to turn on developer mode, then tap this again.",
            Toast.LENGTH_LONG,
        ).show()
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, HeadUnitServerAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun promptEnableAccessibilityService(context: Context) {
        Toast.makeText(
            context,
            "Enable the CarAALink accessibility service, then tap Start head unit server again",
            Toast.LENGTH_LONG
        ).show()
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
