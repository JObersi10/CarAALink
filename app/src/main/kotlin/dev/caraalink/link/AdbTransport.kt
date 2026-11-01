package dev.caraalink.link

import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device side of the ADB-over-AOA link the head unit speaks. Wire behaviour
 * matches what was confirmed from CarbitLink's libjadbd (claude/roadmap.md):
 * a 24-byte zero write before reading, jadbd's exact CNXN reply, and header
 * and payload sent as separate writes. Streams follow the public ADB spec:
 * OPEN -> OKAY(local, remote), WRTE needs an OKAY before the next WRTE.
 */
class AdbTransport(
    private val conn: UsbAccessoryConnection,
    private val logger: DiagnosticLogger,
    private val handlerFor: (service: String) -> StreamHandler?,
    private val onClosed: (reason: String) -> Unit,
) {

    interface StreamHandler {
        fun onOpen(stream: Stream) {}
        fun onData(stream: Stream, data: ByteArray)
        fun onClose(stream: Stream) {}
    }

    inner class Stream(val localId: Int, val remoteId: Int, val service: String, val handler: StreamHandler) {
        private val queue = ArrayDeque<ByteArray>()
        private var canSend = true
        @Volatile var closed = false
            private set
        private var queuedBytes = 0

        /** Queues [data] (split to maxdata chunks); sent one WRTE per remote OKAY. */
        fun write(data: ByteArray) {
            if (closed || data.isEmpty()) return
            synchronized(this) {
                var off = 0
                while (off < data.size) {
                    val n = minOf(maxData, data.size - off)
                    queue.add(data.copyOfRange(off, off + n))
                    queuedBytes += n
                    off += n
                }
            }
            pump()
        }

        fun backlogBytes(): Int = synchronized(this) { queuedBytes }

        fun clearBacklog() = synchronized(this) {
            queue.clear()
            queuedBytes = 0
        }

        internal fun onRemoteOkay() {
            synchronized(this) { canSend = true }
            pump()
        }

        private fun pump() {
            val chunk = synchronized(this) {
                if (!canSend || closed) return
                val c = queue.poll() ?: return
                queuedBytes -= c.size
                canSend = false
                c
            }
            sendPacket(CMD_WRTE, localId, remoteId, chunk)
        }

        fun close() {
            if (closed) return
            closed = true
            sendPacket(CMD_CLSE, localId, remoteId, EMPTY)
            streams.remove(localId)
            handler.onClose(this)
        }

        internal fun closedByRemote() {
            if (closed) return
            closed = true
            streams.remove(localId)
            handler.onClose(this)
        }
    }

    private val running = AtomicBoolean(false)
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextLocalId = AtomicInteger(1)
    private val writeLock = Any()
    @Volatile private var maxData = 4096
    private var readThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        readThread = Thread({
            sendZeroKick()
            readLoop()
        }, "AdbTransport").also { it.start() }
    }

    fun stop(reason: String = "stopped") {
        if (!running.compareAndSet(true, false)) return
        streams.values.toList().forEach { it.closedByRemote() }
        conn.close()
        readThread?.interrupt()
        logger.log("Transport stopped: $reason")
        onClosed(reason)
    }

    private fun sendZeroKick() {
        repeat(50) { attempt ->
            try {
                synchronized(writeLock) { conn.output.write(ByteArray(24)); conn.output.flush() }
                return
            } catch (e: IOException) {
                logger.log("zero kick attempt ${attempt + 1} failed: ${e.message}")
                Thread.sleep(20)
            }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(64 * 1024)
        var pending = ByteArray(0)
        var reason = "eof"
        try {
            while (running.get()) {
                val n = conn.input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                pending += buffer.copyOfRange(0, n)
                while (true) {
                    while (pending.size >= 24 && !headerValid(pending)) pending = pending.copyOfRange(1, pending.size)
                    if (pending.size < 24) break
                    val hb = ByteBuffer.wrap(pending, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
                    val len = hb.getInt(12)
                    if (len < 0 || pending.size < 24 + len) break
                    val payload = pending.copyOfRange(24, 24 + len)
                    handlePacket(hb.getInt(0), hb.getInt(4), hb.getInt(8), payload)
                    pending = pending.copyOfRange(24 + len, pending.size)
                }
            }
        } catch (e: IOException) {
            reason = e.message ?: "io error"
        } catch (e: Exception) {
            Log.e(TAG, "readLoop crashed", e)
            reason = "crash: ${e.message}"
        }
        stop(reason)
    }

    private fun handlePacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        when (cmd) {
            CMD_CNXN -> {
                // Values per libjadbd TransportBase::handleConn (claude/roadmap.md).
                val isNew = (arg0 == VERSION || arg0 == VERSION_SKIP_CHECKSUM) && arg1 != 0x1000
                maxData = if (isNew) arg1 else 4096
                logger.log("CNXN from head unit v=0x${Integer.toHexString(arg0)} maxdata=$arg1 '${ascii(payload)}'")
                // Old streams belong to the previous connection.
                streams.values.toList().forEach { it.closedByRemote() }
                sendPacket(CMD_CNXN, if (isNew) VERSION_SKIP_CHECKSUM else VERSION, maxData, BANNER)
            }
            CMD_OPEN -> {
                val service = ascii(payload).trimEnd('\u0000')
                val handler = handlerFor(service)
                if (handler == null) {
                    logger.log("OPEN '$service' remote=$arg0 -> refused")
                    sendPacket(CMD_CLSE, 0, arg0, EMPTY)
                    return
                }
                val stream = Stream(nextLocalId.getAndIncrement(), arg0, service, handler)
                streams[stream.localId] = stream
                logger.log("OPEN '$service' remote=$arg0 -> local=${stream.localId}")
                sendPacket(CMD_OKAY, stream.localId, arg0, EMPTY)
                handler.onOpen(stream)
            }
            CMD_WRTE -> {
                val stream = streams[arg1]
                if (stream == null) {
                    sendPacket(CMD_CLSE, 0, arg0, EMPTY)
                    return
                }
                sendPacket(CMD_OKAY, stream.localId, stream.remoteId, EMPTY)
                try {
                    stream.handler.onData(stream, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "handler for ${stream.service} threw", e)
                    logger.log("handler ${stream.service} threw: $e")
                }
            }
            CMD_OKAY -> streams[arg1]?.onRemoteOkay()
            CMD_CLSE -> {
                val stream = streams[arg1]
                if (stream != null) {
                    logger.log("CLSE '${stream.service}' local=${stream.localId}")
                    stream.closedByRemote()
                }
            }
            else -> logger.log("unhandled ADB cmd 0x${Integer.toHexString(cmd)}")
        }
    }

    private fun sendPacket(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(arg0).putInt(arg1).putInt(payload.size)
            .putInt(payload.sumOf { it.toInt() and 0xFF })
            .putInt(cmd xor -1).array()
        try {
            synchronized(writeLock) {
                conn.output.write(header)
                if (payload.isNotEmpty()) conn.output.write(payload)
                conn.output.flush()
            }
        } catch (e: IOException) {
            logger.log("write failed: ${e.message}")
            Thread { stop("write failed: ${e.message}") }.start()
        }
    }

    private fun headerValid(b: ByteArray): Boolean {
        val bb = ByteBuffer.wrap(b, 0, 24).order(ByteOrder.LITTLE_ENDIAN)
        return bb.getInt(20) == bb.getInt(0) xor -1
    }

    private fun ascii(b: ByteArray) = String(b, Charsets.US_ASCII)

    companion object {
        private const val TAG = "AdbTransport"
        private const val VERSION = 0x01000000
        private const val VERSION_SKIP_CHECKSUM = 0x01000001
        private val EMPTY = ByteArray(0)
        private val BANNER = "device::ro.product.name=jadbd".toByteArray(Charsets.US_ASCII)

        private fun cmd(s: String) = s[0].code or (s[1].code shl 8) or (s[2].code shl 16) or (s[3].code shl 24)
        val CMD_CNXN = cmd("CNXN")
        val CMD_OPEN = cmd("OPEN")
        val CMD_OKAY = cmd("OKAY")
        val CMD_CLSE = cmd("CLSE")
        val CMD_WRTE = cmd("WRTE")
    }
}
