package dev.caraalink.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * One-click entry point: launches Android Auto and asks the accessibility
 * service to drive its "Start head unit server" developer toggle, so the
 * user doesn't have to do Settings -> Version x10 -> overflow -> Start by
 * hand every time (see HeadUnitServerAccessibilityService).
 */
object HeadUnitServerLauncher {

    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    fun start(context: Context) {
        if (!isAccessibilityServiceEnabled(context)) {
            promptEnableAccessibilityService(context)
            return
        }

        if (!launchAndroidAuto(context)) {
            Toast.makeText(context, "Android Auto isn't installed", Toast.LENGTH_LONG).show()
            return
        }

        instance()?.requestHeadUnitServerStart()
    }

    private fun instance(): HeadUnitServerAccessibilityService? =
        HeadUnitServerAccessibilityService.instance

    private fun launchAndroidAuto(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
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
