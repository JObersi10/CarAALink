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
    private const val KEY_TRACE = "last_crash_trace"

    fun recordCrash(context: Context, threadName: String, throwable: Throwable) {
        val summary = "${throwable.javaClass.simpleName}: ${throwable.message} (on $threadName)"
        val trace = Log.getStackTraceString(throwable)
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SUMMARY, summary).putString(KEY_TRACE, trace).apply()
        }.onFailure { Log.w("CrashMarker", "couldn't persist crash marker", it) }
    }

    /**
     * Non-clearing read of the full trace, so [CarAALinkApplication] can
     * carry a previous crash into the *new* log file the next launch
     * starts -- without this, the crash trace only ever lived in whatever
     * (now old, already-rolled-over) file was open at the moment it
     * happened, so exporting "the log" after a crash and restart wouldn't
     * actually show it. Doesn't clear the marker; [consumeLastCrash] still
     * does that for the one-time Toast.
     */
    fun peekLastCrashTrace(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TRACE, null)

    /** Returns and clears the marker -- a one-time "we crashed last launch" notice, not a log. */
    fun consumeLastCrash(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val summary = prefs.getString(KEY_SUMMARY, null)
        if (summary != null) prefs.edit().remove(KEY_SUMMARY).remove(KEY_TRACE).apply()
        return summary
    }
}
