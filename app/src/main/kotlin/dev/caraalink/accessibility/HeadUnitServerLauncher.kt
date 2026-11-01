package dev.caraalink.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * One-click entry point: launches Android Auto and asks the accessibility
 * service to drive its "Start head unit server" developer toggle, so the
 * user doesn't have to do Settings -> Version x10 -> overflow -> Start by
 * hand every time (see HeadUnitServerAccessibilityService).
 */
object HeadUnitServerLauncher {

    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    private const val TAG = "HeadUnitServerLauncher"

    fun start(context: Context) {
        if (!isAccessibilityServiceEnabled(context)) {
            promptEnableAccessibilityService(context)
            return
        }

        if (launchAndroidAuto(context)) {
            instance()?.requestHeadUnitServerStart()
            return
        }

        // Didn't manage to bring AA's own UI to the front -- don't run the
        // automation against whatever screen happens to be open. Offer the
        // app-info page as a manual way in instead.
        Toast.makeText(
            context,
            "Couldn't open Android Auto automatically — opening its app info instead; launch it from there, then retry",
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

    private fun instance(): HeadUnitServerAccessibilityService? =
        HeadUnitServerAccessibilityService.instance

    /**
     * Android Auto often has no standard LAUNCHER activity (it's designed to
     * run invisibly in the background on most phones), so
     * [PackageManager.getLaunchIntentForPackage] usually returns null for it
     * even when it's installed — that's the "Android Auto isn't installed"
     * false negative. We fall back to resolving any activity the package
     * declares that answers ACTION_MAIN, launching it explicitly by the
     * component PackageManager gives us at runtime (never a hardcoded,
     * version-specific class name).
     */
    private fun launchAndroidAuto(context: Context): Boolean {
        val pm = context.packageManager

        pm.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
            return true
        }

        val probe = Intent(Intent.ACTION_MAIN).setPackage(ANDROID_AUTO_PACKAGE)
        val resolved = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?: pm.queryIntentActivities(probe, 0).firstOrNull()?.also {
                Log.i(TAG, "Resolved $ANDROID_AUTO_PACKAGE via queryIntentActivities fallback")
            }
        val activityInfo = resolved?.activityInfo ?: return false
        val component = ComponentName(activityInfo.packageName, activityInfo.name)
        context.startActivity(
            Intent(Intent.ACTION_MAIN).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
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
