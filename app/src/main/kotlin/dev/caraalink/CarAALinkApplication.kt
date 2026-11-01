package dev.caraalink

import android.app.Application
import android.os.Process
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Starts a persistent, in-app logger the moment the process starts, so
 * diagnostic data stops depending on a live adb/wireless-debugging
 * connection staying up (that connection has repeatedly died mid-test this
 * session -- see claude/roadmap.md). Captures this process's own logcat
 * output, crashes included, to a rotating on-device file via
 * [DiagnosticLogger]. For now this overlaps with the USB sniffer's own
 * logging (both land in the same logs/ directory) rather than being fully
 * separate -- that's deliberate for now, not a final design.
 */
class CarAALinkApplication : Application() {

    private val logger by lazy { DiagnosticLogger(this, "app") }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        startSelfLogcatCapture()
        logger.log("CarAALinkApplication.onCreate")
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                logger.log("FATAL on ${thread.name}: ${Log.getStackTraceString(throwable)}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Apps can read their own process's logcat output without any special
     * permission -- logd scopes what a non-system UID sees to itself. The
     * explicit --pid makes that intent clear rather than relying on it.
     */
    private fun startSelfLogcatCapture() {
        Thread({
            try {
                val pid = Process.myPid().toString()
                val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid", pid)
                    .redirectErrorStream(true)
                    .start()
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        logger.log(line)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Self-logcat capture failed to start", e)
            }
        }, "SelfLogcatCapture").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "CarAALinkApplication"
    }
}
