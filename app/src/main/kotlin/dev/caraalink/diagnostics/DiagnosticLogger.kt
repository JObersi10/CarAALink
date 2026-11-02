package dev.caraalink.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes timestamped lines to ONE shared, app-wide log file (no more
 * separate `aa-session-*.log` / `car-session-*.log` / `usb-sniff-*.log`
 * files that needed merging to read side by side) -- every subsystem's
 * [DiagnosticLogger] instance, whatever [tag] it's constructed with, writes
 * into the same rotating current file, each line prefixed with that tag.
 * Purely a diagnostic tool -- nothing it writes is consumed by the app
 * itself.
 */
class DiagnosticLogger(private val context: Context, private val tag: String) {

    @Synchronized
    fun log(line: String) = write("[$tag] $line")

    /** Logs [bytes] as a classic hex+ASCII dump, [direction] e.g. "RX"/"TX". */
    fun logBytes(direction: String, bytes: ByteArray, length: Int) {
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in 0 until length) {
            val b = bytes[i]
            hex.append(String.format("%02X ", b))
            ascii.append(if (b in 0x20..0x7e) b.toInt().toChar() else '.')
        }
        log("$direction ($length bytes): $hex | $ascii")
    }

    fun latestLogFile(): File = currentFile(context)

    /** Builds a share-sheet [Intent] for the current log file. */
    fun shareLatestIntent(): Intent = shareFileIntent(latestLogFile())

    fun shareFileIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    companion object {
        private const val MAX_BYTES_PER_FILE = 4L * 1024 * 1024 // 4 MiB, then roll over
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

        @Volatile
        private var currentFileRef: File? = null

        private fun logDir(context: Context): File =
            File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }

        @Synchronized
        private fun currentFile(context: Context): File {
            val existing = currentFileRef
            if (existing != null && (!existing.exists() || existing.length() <= MAX_BYTES_PER_FILE)) return existing
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            return File(logDir(context), "caraalink-$stamp.log").also { currentFileRef = it }
        }

        @Synchronized
        private fun write(context: Context, line: String) {
            val timestamped = "${TIMESTAMP_FORMAT.format(Date())}  $line\n"
            currentFile(context).appendText(timestamped)
        }

        /** Deletes every rolled-over log file and starts fresh on the next write. */
        @Synchronized
        fun clearAll(context: Context) {
            logDir(context).listFiles()?.forEach { it.delete() }
            currentFileRef = null
        }
    }

    private fun write(line: String) = write(context, line)
}
