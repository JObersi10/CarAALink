package dev.caraalink.diagnostics

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.util.Log
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.usb.UsbAccessoryConnection
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostic-only mode: opens the USB accessory connection (same confirmed
 * accessory role as the real protocol will eventually use) and just logs
 * every byte the head unit sends — no response, no protocol logic. The goal
 * is a real packet dump to work from, without needing a rooted phone for
 * usbmon (see claude/roadmap.md Phase 1's open "needs usbmon capture" item).
 *
 * Caveat, stated plainly: since this doesn't speak the real CarbitLink
 * handshake, the head unit may retry or give up after a timeout. Whatever it
 * sends before that point is still real, useful evidence — this is not a
 * substitute for a full capture, just a way to get a first look without
 * extra hardware.
 */
class UsbSnifferDiagnostic(private val context: Context) {

    private val logger = DiagnosticLogger(context, "usb-sniff")
    private val running = AtomicBoolean(false)
    private var connection: UsbAccessoryConnection? = null
    private var readThread: Thread? = null

    fun isRunning(): Boolean = running.get()

    fun start(accessory: UsbAccessory) {
        if (!running.compareAndSet(false, true)) return

        val conn = UsbAccessoryConnection.open(context, accessory)
        if (conn == null) {
            logger.log("ERROR: UsbManager.openAccessory() returned null")
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "openAccessory() returned null") }
            running.set(false)
            return
        }
        connection = conn

        logger.log("Opened accessory manufacturer=${accessory.manufacturer} model=${accessory.model} version=${accessory.version}")
        ConnectionStateHolder.update {
            it.copy(
                phase = ConnectionPhase.SNIFFING,
                accessoryManufacturer = accessory.manufacturer,
                accessoryModel = accessory.model,
                lastError = null,
            )
        }

        readThread = Thread({ readLoop(conn) }, "UsbSnifferDiagnostic").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        connection?.close()
        connection = null
        readThread?.interrupt()
        readThread = null
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.DISCONNECTED) }
        logger.log("Sniffer stopped")
    }

    fun shareLogIntent() = logger.shareLatestIntent()

    private fun readLoop(conn: UsbAccessoryConnection) {
        val buffer = ByteArray(16 * 1024)
        var totalRead = 0L
        while (running.get()) {
            val n = try {
                conn.input.read(buffer)
            } catch (e: IOException) {
                Log.w(TAG, "read failed", e)
                logger.log("ERROR reading: ${e.message}")
                ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = e.message) }
                break
            }
            if (n < 0) break
            if (n == 0) continue

            totalRead += n
            logger.logBytes("RX", buffer, n)
            ConnectionStateHolder.update { it.copy(bytesRead = totalRead) }
        }
        running.set(false)
    }

    companion object {
        private const val TAG = "UsbSnifferDiagnostic"
    }
}
