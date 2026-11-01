package dev.caraalink.diagnostics

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.util.Log
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.usb.UsbAccessoryConnection
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ACTIVE diagnostic -- unlike [UsbSnifferDiagnostic], which only listens.
 *
 * CONFIRMED by live capture (claude/roadmap.md): the head unit's first move
 * over the USB accessory link is a standard ADB protocol CONNECT ("CNXN" +
 * a "host::..." banner), reproduced identically twice. Both times, nothing
 * answered it, so it gave up after ~8s. The ADB wire protocol is fully
 * public (AOSP system/core/adb/protocol.txt) -- completing just the CNXN
 * handshake here is implementing a published spec, not reverse-engineering
 * anything. The goal is narrow: see what it asks for *after* CONNECT
 * succeeds (an AUTH challenge? an OPEN with some service string?).
 *
 * Deliberately stops there. This does NOT respond OKAY to an OPEN, does NOT
 * answer an AUTH challenge, and executes nothing it receives -- it logs
 * what comes next and nothing more.
 */
class AdbHandshakeProbe(private val context: Context) {

    private val logger = DiagnosticLogger(context, "adb-probe")
    private val running = AtomicBoolean(false)
    private var connection: UsbAccessoryConnection? = null
    private var readThread: Thread? = null

    fun start(accessory: UsbAccessory) {
        Log.i(TAG, "start: ${accessory.manufacturer}/${accessory.model}")
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start: already running")
            return
        }

        val conn = UsbAccessoryConnection.open(context, accessory)
        if (conn == null) {
            Log.e(TAG, "start: openAccessory() returned null")
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "openAccessory() returned null") }
            running.set(false)
            return
        }
        connection = conn
        logger.log("Opened accessory manufacturer=${accessory.manufacturer} model=${accessory.model}")
        ConnectionStateHolder.update {
            it.copy(phase = ConnectionPhase.SNIFFING, accessoryManufacturer = accessory.manufacturer, accessoryModel = accessory.model, lastError = null)
        }

        readThread = Thread({ readLoop(conn) }, "AdbHandshakeProbe").also { it.start() }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "stop")
        connection?.close()
        connection = null
        readThread?.interrupt()
        readThread = null
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.DISCONNECTED) }
        logger.log("Probe stopped")
    }

    private fun readLoop(conn: UsbAccessoryConnection) {
        val buffer = ByteArray(16 * 1024)
        // Header and payload arrive in separate read() calls on this link
        // (confirmed in capture), so accumulate until a whole message is in.
        var pending = ByteArray(0)
        var handshakeSent = false
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
            pending += buffer.copyOfRange(0, n)

            while (true) {
                val (message, consumed) = parseAdbMessage(pending) ?: break
                pending = pending.copyOfRange(consumed, pending.size)
                Log.i(TAG, "RX ADB message: $message")
                logger.log("Parsed ADB message: $message")
                if (message.command == CMD_CNXN && !handshakeSent) {
                    sendConnectReply(conn)
                    handshakeSent = true
                }
            }
            ConnectionStateHolder.update { it.copy(bytesRead = totalRead) }
        }
        running.set(false)
    }

    private fun sendConnectReply(conn: UsbAccessoryConnection) {
        // Banner string CONFIRMED in CarbitLink's libjadbd.so (strings); the
        // head unit sat silent for 24s after our own "caraalink" banner.
        // Trailing NUL mirrors the head unit's own "host::N9r8\0".
        val payload = "device::ro.product.name=jadbd\u0000".toByteArray(Charsets.US_ASCII)
        val message = buildAdbMessage(CMD_CNXN, VERSION, MAX_DATA, payload)
        Log.i(TAG, "TX CNXN reply (${message.size} bytes)")
        logger.log("TX CNXN reply (${message.size} bytes)")
        try {
            conn.output.write(message)
            conn.output.flush()
        } catch (e: IOException) {
            Log.w(TAG, "write failed", e)
            logger.log("ERROR writing CNXN reply: ${e.message}")
        }
    }

    private data class AdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val dataLength: Int,
        val dataCheck: Int,
        val payload: ByteArray,
    ) {
        override fun toString() =
            "cmd=${commandName(command)} arg0=0x${Integer.toHexString(arg0)} arg1=$arg1 " +
                "dataLength=$dataLength dataCheck=0x${Integer.toHexString(dataCheck)} " +
                "payload=${payload.toString(Charsets.US_ASCII).replace("\u0000", "\\0")}"
    }

    /**
     * Returns the first complete ADB message in [bytes] and how many bytes it
     * used, or null if a whole header + payload hasn't arrived yet.
     */
    private fun parseAdbMessage(bytes: ByteArray): Pair<AdbMessage, Int>? {
        if (bytes.size < 24) return null
        val bb = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val dataLength = bb.int
        val dataCheck = bb.int
        bb.int // magic -- not re-validated here
        if (dataLength < 0 || bytes.size < 24 + dataLength) return null
        val payload = bytes.copyOfRange(24, 24 + dataLength)
        return AdbMessage(command, arg0, arg1, dataLength, dataCheck, payload) to (24 + dataLength)
    }

    private fun buildAdbMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(command)
        bb.putInt(arg0)
        bb.putInt(arg1)
        bb.putInt(payload.size)
        // Public ADB spec: protocol version 0x01000000 (what the head unit
        // announced) requires data_check = sum of payload bytes; only
        // 0x01000001+ may skip it. Sending 0 here likely got our reply
        // rejected -- the head unit reset the link ~4s later.
        bb.putInt(payload.sumOf { it.toInt() and 0xFF })
        bb.putInt(command xor -1) // magic = command ^ 0xFFFFFFFF
        bb.put(payload)
        return bb.array()
    }

    companion object {
        fun commandName(command: Int): String = when (command) {
            CMD_CNXN -> "CNXN"
            CMD_AUTH -> "AUTH"
            CMD_OPEN -> "OPEN"
            CMD_OKAY -> "OKAY"
            CMD_CLSE -> "CLSE"
            CMD_WRTE -> "WRTE"
            else -> "0x${Integer.toHexString(command)}"
        }

        private const val TAG = "AdbHandshakeProbe"
        private const val VERSION = 0x01000000 // matches the version the head unit sent us
        private const val MAX_DATA = 4096

        // Public ADB protocol command constants (AOSP system/core/adb/protocol.txt),
        // not anything proprietary to CarbitLink.
        private val CMD_CNXN = commandInt("CNXN")
        private val CMD_AUTH = commandInt("AUTH")
        private val CMD_OPEN = commandInt("OPEN")
        private val CMD_OKAY = commandInt("OKAY")
        private val CMD_CLSE = commandInt("CLSE")
        private val CMD_WRTE = commandInt("WRTE")

        private fun commandInt(s: String): Int =
            s[0].code or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)
    }
}
