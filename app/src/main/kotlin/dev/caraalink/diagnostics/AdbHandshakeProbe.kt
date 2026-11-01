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

        readThread = Thread({
            sendZeroKick(conn)
            readLoop(conn)
        }, "AdbHandshakeProbe").also { it.start() }
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
                // Stale bytes from a previous session can precede the first
                // real header; drop until a header's magic checks out.
                while (pending.size >= 24 && !headerMagicValid(pending)) {
                    pending = pending.copyOfRange(1, pending.size)
                }
                val (message, consumed) = parseAdbMessage(pending) ?: break
                pending = pending.copyOfRange(consumed, pending.size)
                Log.i(TAG, "RX ADB message: $message")
                logger.log("Parsed ADB message: $message")
                if (message.command == CMD_CNXN && !handshakeSent) {
                    sendConnectReply(conn, message.arg0, message.arg1)
                    handshakeSent = true
                } else if (message.command == CMD_OPEN || message.command == CMD_WRTE) {
                    // Public ADB: OKAY(local-id, remote-id) accepts an OPEN and
                    // acks each WRTE. Data is only logged, never executed.
                    send(conn, "OKAY", buildAdbMessage(CMD_OKAY, LOCAL_STREAM_ID, message.arg0, ByteArray(0)))
                    if (message.command == CMD_WRTE && !maybeAnswerClientInfo(conn, message)) {
                        maybeAnswerPxcHead(conn, message)
                    }
                }
            }
            ConnectionStateHolder.update { it.copy(bytesRead = totalRead) }
        }
        // Link died on its own: release the accessory fd, or Android won't
        // offer the app chooser on the next plug-in until we're force-quit.
        if (running.compareAndSet(true, false)) {
            conn.close()
            connection = null
        }
    }

    /**
     * Reply values CONFIRMED from libjadbd.so TransportBase::handleConn
     * (disassembly, see claude/roadmap.md): if the host's maxdata isn't
     * 0x1000 (ours sends 0x4000), jadbd replies version 0x01000001 and
     * echoes the host's maxdata; otherwise 0x01000000 / 4096. Banner has
     * no trailing NUL (data_length = snprintf return).
     */
    private fun sendConnectReply(conn: UsbAccessoryConnection, hostVersion: Int, hostMaxData: Int) {
        val newVersion = (hostVersion == VERSION || hostVersion == VERSION_SKIP_CHECKSUM) && hostMaxData != MAX_DATA
        val version = if (newVersion) VERSION_SKIP_CHECKSUM else VERSION
        val maxData = if (newVersion) hostMaxData else MAX_DATA
        val payload = "device::ro.product.name=jadbd".toByteArray(Charsets.US_ASCII)
        send(conn, "CNXN v=0x${Integer.toHexString(version)} maxdata=$maxData", buildAdbMessage(CMD_CNXN, version, maxData, payload))
    }

    /**
     * CONFIRMED from libjadbd AOATransport::outputThread (@0x37e2c): before
     * reading anything, jadbd writes 24 zero bytes to the accessory, retrying
     * up to 50 times with 20 ms sleeps until the write succeeds.
     */
    private fun sendZeroKick(conn: UsbAccessoryConnection) {
        repeat(50) { attempt ->
            try {
                conn.output.write(ByteArray(24))
                conn.output.flush()
                logger.log("TX 24 zero bytes (attempt ${attempt + 1})")
                return
            } catch (e: IOException) {
                logger.log("zero kick write failed (attempt ${attempt + 1}): ${e.message}")
                Thread.sleep(20)
            }
        }
    }

    /**
     * PXC 16-byte head (LE): cmd, totalLen, cmd^totalLen, extra. CONFIRMED
     * from CarbitLink's CmdBaseHead + server accept switch: for a channel
     * open head (cmd 0x10000 = main control channel), the server echoes the
     * head with cmd+1, len 16, recomputed magic, extra word unchanged.
     */
    private fun maybeAnswerPxcHead(conn: UsbAccessoryConnection, message: AdbMessage) {
        val p = message.payload
        if (p.size != 16) return
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(0)
        val len = bb.getInt(4)
        if (cmd xor len != bb.getInt(8) || cmd != PXC_MAIN_CHANNEL) return
        val reply = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd + 1).putInt(16).putInt((cmd + 1) xor 16).putInt(bb.getInt(12)).array()
        send(conn, "WRTE pxc head 0x${Integer.toHexString(cmd + 1)}", buildAdbMessage(CMD_WRTE, LOCAL_STREAM_ID, message.arg0, reply))
    }

    /**
     * Reply to C2P CLIENT_INFO (0x10010) with P2C 0x10011 + JSON. Key names
     * CONFIRMED from CarbitLink's ECP_C2P_CLIENT_INFO reply builder. Its RSA
     * pubkey/encryptedHUID fields are deliberately omitted (their licensing
     * keys; the head unit reported enableSockServerAuth=false).
     */
    private fun maybeAnswerClientInfo(conn: UsbAccessoryConnection, message: AdbMessage): Boolean {
        val p = message.payload
        if (p.size < 16) return false
        val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.getInt(0) != PXC_CLIENT_INFO || bb.getInt(0) xor bb.getInt(4) != bb.getInt(8)) return false
        val json = org.json.JSONObject()
            .put("pxcVersion", "1.0.2")
            .put("phoneUUID", "caraalink-" + android.os.Build.MODEL.replace(' ', '_'))
            .put("phoneBrand", android.os.Build.BRAND)
            .put("phoneModel", android.os.Build.MODEL)
            .put("phoneOsVersion", android.os.Build.VERSION.SDK_INT.toString())
            .put("phoneOs", "android")
            .put("token", 0)
            .put("supportH264IFrame", true)
            .toString().toByteArray(Charsets.UTF_8)
        val total = 16 + json.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(PXC_CLIENT_INFO + 1).putInt(total).putInt((PXC_CLIENT_INFO + 1) xor total).putInt(0)
            .put(json).array()
        send(conn, "WRTE pxc 0x10011 client-info reply", buildAdbMessage(CMD_WRTE, LOCAL_STREAM_ID, message.arg0, out))
        return true
    }

    /** Header and payload go out as separate writes, like jadbd's outputThread. */
    private fun send(conn: UsbAccessoryConnection, label: String, message: ByteArray) {
        Log.i(TAG, "TX $label (${message.size} bytes)")
        logger.log("TX $label (${message.size} bytes)")
        try {
            conn.output.write(message, 0, 24)
            conn.output.flush()
            if (message.size > 24) {
                conn.output.write(message, 24, message.size - 24)
                conn.output.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "write failed", e)
            logger.log("ERROR writing $label: ${e.message}")
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
        val magic = bb.int
        if (magic != command xor -1) return null
        if (dataLength < 0 || bytes.size < 24 + dataLength) return null
        val payload = bytes.copyOfRange(24, 24 + dataLength)
        return AdbMessage(command, arg0, arg1, dataLength, dataCheck, payload) to (24 + dataLength)
    }

    private fun headerMagicValid(bytes: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        return bb.getInt(20) == bb.getInt(0) xor -1
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
        private const val VERSION = 0x01000000
        private const val VERSION_SKIP_CHECKSUM = 0x01000001
        private const val LOCAL_STREAM_ID = 1
        private const val PXC_MAIN_CHANNEL = 0x10000
        private const val PXC_CLIENT_INFO = 0x10010
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
