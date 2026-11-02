package dev.caraalink.diagnostics

import android.content.Context
import android.util.Log

/**
 * One-shot "did we crash last run" flag, purely local (a SharedPreferences
 * entry, not a network upload -- there's no telemetry backend). The full
 * stack trace already lands in the unified log via
 * [CarAALinkApplication]'s crash handler; this just makes the *fact* of a
 * crash visible on the next launch without having to go dig through an
 * exported log file to notice one happened.
 */
object CrashMarker {
    private const val PREFS = "crash_marker"
    private const val KEY_SUMMARY = "last_crash_summary"

    fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        val summary = "${throwable.javaClass.simpleName}: ${throwable.message} (on $threadName)"
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SUMMARY, summary).apply()
        }.onFailure { Log.w("CrashMarker", "couldn't persist crash marker", it) }
    }

    /** Returns and clears the marker -- a one-time "we crashed last launch" notice, not a log. */
    fun consumeLastCrash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val summary = prefs.getString(KEY_SUMMARY, null)
        if (summary != null) prefs.edit().remove(KEY_SUMMARY).apply()
        return summary
    }
}
