package dev.caraalink.settings

import android.content.Context
import org.json.JSONObject

/**
 * Named snapshots of the video/AA settings that actually vary per car
 * (screen shape, resolution, DPI, fill mode, bitrate, audio routing) --
 * multi-car management from claude/roadmap.md's feasibility check, scoped
 * down to "save/load a settings snapshot by name" rather than a full
 * per-car device/connection registry, which this app has no concept of
 * (CarbitLink doesn't identify *which* car is connected, just that one is).
 */
object CarProfiles {
    private const val PREFS_FILE = "car_profiles"

    /** Keys worth remembering per car; everything else in Settings is phone-wide, not car-specific. */
    private val PROFILE_KEYS = listOf(
        "car_orientation",
        "video_resolution_mode",
        "video_resolution_custom_width",
        "video_resolution_custom_height",
        "video_frame_rate",
        "video_bitrate_kbps",
        "video_dpi_auto",
        "video_dpi_manual",
        "aa_fill_mode",
        "aa_audio_route",
    )

    private fun store(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    private fun logger(context: Context) = dev.caraalink.diagnostics.DiagnosticLogger(context, "car-profiles")

    fun listProfiles(context: Context): List<String> =
        store(context).all.keys.sorted()

    fun saveProfile(context: Context, name: String) {
        if (name.isBlank()) return
        val defaults = PreferenceManagerCompat.default(context)
        val snapshot = JSONObject()
        for (key in PROFILE_KEYS) {
            when (val value = defaults.all[key]) {
                is Boolean -> snapshot.put(key, value)
                is String -> snapshot.put(key, value)
                null -> {}
                else -> snapshot.put(key, value.toString())
            }
        }
        store(context).edit().putString(name.trim(), snapshot.toString()).apply()
        logger(context).log("saved profile '${name.trim()}': $snapshot")
    }

    /** Returns false if [name] isn't a saved profile. */
    fun loadProfile(context: Context, name: String): Boolean {
        val json = store(context).getString(name, null) ?: return false
        val snapshot = JSONObject(json)
        val defaults = PreferenceManagerCompat.default(context)
        val editor = defaults.edit()
        for (key in PROFILE_KEYS) {
            if (!snapshot.has(key)) continue
            when (val value = snapshot.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                else -> editor.putString(key, value.toString())
            }
        }
        editor.apply()
        logger(context).log("loaded profile '$name': $snapshot")
        return true
    }

    fun deleteProfile(context: Context, name: String) {
        store(context).edit().remove(name).apply()
        logger(context).log("deleted profile '$name'")
    }
}

/** Thin wrapper so CarProfiles doesn't need an androidx.preference import for one call. */
private object PreferenceManagerCompat {
    fun default(context: Context) = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
}
