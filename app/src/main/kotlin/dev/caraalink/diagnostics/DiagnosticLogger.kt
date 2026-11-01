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
 * Writes timestamped hex+ASCII dumps of USB traffic (or anything else worth
 * capturing for RE) to a rotating log file under this app's own external
 * files dir, and can hand the latest file to the share sheet so it's easy to
 * get off the phone and into a conversation here. Purely a diagnostic tool —
 * nothing it writes is consumed by the app itself.
 */
class DiagnosticLogger(private val context: Context, private val tag: String) {

    private val maxBytesPerFile = 4L * 1024 * 1024 // 4 MiB, then roll over
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    private val logDir: File
        get() = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }

    @Volatile
    private var currentFile: File = newFile()

    private fun newFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(logDir, "$tag-$stamp.log")
    }

    @Synchronized
    fun log(line: String) {
        if (currentFile.exists() && currentFile.length() > maxBytesPerFile) {
            currentFile = newFile()
        }
        val timestamped = "${timestampFormat.format(Date())}  $line\n"
        currentFile.appendText(timestamped)
    }

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

    fun latestLogFile(): File = currentFile

    fun allLogFiles(): List<File> = logDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    /**
     * Merges every tag's most recent log into ONE chronologically interleaved
     * file, so a single share is enough to see the app log, the car session,
     * the AA TLS receiver and the USB probes side by side on the same
     * timeline. Lines are sorted on their leading timestamp, which is the
     * `yyyy-MM-dd'T'HH:mm:ss.SSS` prefix [log] writes.
     */
    fun mergedLogFile(): File? {
        val latestPerTag = allLogFiles()
            .filterNot { it.name.startsWith("all-logs-") }
            .groupBy { TAG_STAMP.find(it.name)?.groupValues?.get(1) ?: it.name }
            .mapNotNull { (_, files) -> files.maxByOrNull { it.lastModified() } }
        if (latestPerTag.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(logDir, "all-logs-$stamp.log")
        val merged = latestPerTag
            .flatMap { f -> f.readLines() }
            .filter { it.isNotBlank() }
            .sortedBy { it.take(23) }

        out.writeText(
            buildString {
                append("# CarAALink merged log $stamp\n")
                append("# sources: ${latestPerTag.map { it.name }.sorted().joinToString(", ")}\n")
                append("# lines: ${merged.size}\n\n")
                merged.forEach { append(it).append('\n') }
            }
        )
        return out
    }

    /** Builds a share-sheet [Intent] for the most recent log file. */
    fun shareLatestIntent(): Intent = shareFileIntent(currentFile)

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
        /** `car-session-20261101-163925.log` -> `car-session`. */
        private val TAG_STAMP = Regex("""^(.+)-\d{8}-\d{6}\.log$""")

        /** Shares several files (e.g. the app log and the USB sniffer log) in one go. */
        fun shareFilesIntent(context: Context, files: List<File>): Intent {
            val uris = ArrayList(files.map {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
            })
            return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
