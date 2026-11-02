package dev.caraalink.settings

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Typed reads over the preferences declared in res/xml/root_preferences.xml.
 * Nothing in the capture/protocol pipeline consumes these yet — they're wired
 * up as that code lands (see claude/roadmap.md Phase 2/3). Keys here must
 * stay in sync with root_preferences.xml.
 */
object AppSettings {

    enum class ResolutionMode { AUTO, CUSTOM }
    enum class UsbRoleOverride { AUTO, FORCE_ACCESSORY, FORCE_HOST }
    enum class AaAudioRoute { BT_PREFERRED, DEVICE_ONLY }

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    fun resolutionMode(context: Context): ResolutionMode =
        when (prefs(context).getString("video_resolution_mode", "auto")) {
            "custom" -> ResolutionMode.CUSTOM
            else -> ResolutionMode.AUTO
        }

    /** Only meaningful when [resolutionMode] is CUSTOM; null falls back to AUTO behavior. */
    fun customResolution(context: Context): Pair<Int, Int>? {
        val w = prefs(context).getString("video_resolution_custom_width", null)?.toIntOrNull()
        val h = prefs(context).getString("video_resolution_custom_height", null)?.toIntOrNull()
        return if (w != null && h != null) w to h else null
    }

    fun frameRate(context: Context): Int =
        prefs(context).getString("video_frame_rate", "30")?.toIntOrNull() ?: 30

    fun videoBitrateKbps(context: Context): Int =
        prefs(context).getString("video_bitrate_kbps", "6000")?.toIntOrNull() ?: 6000

    /** Auto-detect uses this phone's own screen density; manual is a straight AA `density` value. */
    fun videoDpi(context: Context): Int {
        if (prefs(context).getBoolean("video_dpi_auto", true)) {
            return context.resources.displayMetrics.densityDpi
        }
        return prefs(context).getString("video_dpi_manual", "160")?.toIntOrNull() ?: 160
    }

    fun audioSampleRateHz(context: Context): Int =
        prefs(context).getString("audio_sample_rate", "48000")?.toIntOrNull() ?: 48000

    fun audioStereo(context: Context): Boolean =
        prefs(context).getBoolean("audio_stereo", true)

    fun autoStartHeadUnitServer(context: Context): Boolean =
        prefs(context).getBoolean("auto_start_head_unit_server", true)

    fun autoReconnectUsb(context: Context): Boolean =
        prefs(context).getBoolean("auto_reconnect_usb", true)

    /** Debug-only. USB role is CONFIRMED (phone = accessory) — see claude/roadmap.md. */
    fun usbRoleOverride(context: Context): UsbRoleOverride =
        when (prefs(context).getString("usb_role_override", "auto")) {
            "accessory" -> UsbRoleOverride.FORCE_ACCESSORY
            "host" -> UsbRoleOverride.FORCE_HOST
            else -> UsbRoleOverride.AUTO
        }

    fun verboseLogging(context: Context): Boolean =
        prefs(context).getBoolean("debug_verbose_logging", false)

    fun debugOverlay(context: Context): Boolean =
        prefs(context).getBoolean("debug_overlay", false)

    fun aaAutoLaunch(context: Context): Boolean =
        prefs(context).getBoolean("aa_auto_launch", false)

    fun aaAudioRoute(context: Context): AaAudioRoute =
        when (prefs(context).getString("aa_audio_route", "bt_preferred")) {
            "device_only" -> AaAudioRoute.DEVICE_ONLY
            else -> AaAudioRoute.BT_PREFERRED
        }
}
