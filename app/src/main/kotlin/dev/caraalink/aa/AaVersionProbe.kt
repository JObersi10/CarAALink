package dev.caraalink.aa

import android.content.Context
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rung 1 of the Android Auto receiver: speak the plaintext version exchange.
 *
 * The prior finding (live, 2026-11-01) was that both TLS roles simply timed
 * out against 127.0.0.1:5277 — because the gearhead is not expecting TLS on
 * the socket at all. `EncryptionType { PLAIN, ENCRYPTED }` and
 * `ControlServiceChannel::sendHandshake()` in aasdk show the real shape:
 * AA frames are individually flagged, and even the TLS handshake is carried
 * *inside* PLAIN control messages (`MESSAGE_ENCAPSULATED_SSL`, id 3). So the
 * socket always speaks AA frames; TLS is payload, not the transport.
 *
 * This class does only the first move — send VERSION_REQUEST and decode
 * whatever comes back. It deliberately stops there: no TLS, no service
 * discovery, no channel opens.
 */
class AaVersionProbe(
    private val context: Context,
    private val config: Config = Config(),
) {

    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 5277,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 12_000,
        /** Stop after this many frames so a chatty peer can't fill the log. */
        val maxFrames: Int = 40,
    )

    companion object {
        private const val TAG = "AaVersionProbe"

        @Volatile
        var active: AaVersionProbe? = null
            private set
    }

    private val logger = DiagnosticLogger(context, "aa-version")
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start: already running")
            return
        }
        logger.log("=== AA version probe ${config.host}:${config.port}")
        Log.i(TAG, "start ${config.host}:${config.port}")
        worker = Thread({ run() }, "AaVersionProbe").apply { isDaemon = true }
        active = this
        worker!!.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        logger.log("=== probe stopped")
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
        if (active === this) active = null
    }

    private fun run() {
        try {
            val s = Socket().apply {
                connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
                soTimeout = config.readTimeoutMs
            }
            socket = s
            logger.log("tcp connected local=${s.localSocketAddress} remote=${s.remoteSocketAddress}")

            val request = AaFraming.versionRequest()
            logger.log("TX version request (${request.size}B)")
            logger.logBytes("TX", request, request.size)
            s.getOutputStream().apply { write(request); flush() }

            val input = DataInputStream(s.getInputStream())
            var index = 0
            while (running.get() && index < config.maxFrames) {
                val frame = readFrame(input)
                index++
                logFrame(index, frame)

                if (frame.channelId == AaFraming.CHANNEL_CONTROL && frame.isPlain) {
                    val id = AaFraming.controlMessageId(frame.payload)
                    if (id == AaFraming.MSG_VERSION_RESPONSE) {
                        val (major, minor) = versionFrom(frame.payload)
                        logger.log("VERSION_RESPONSE major=$major minor=$minor -- rung 1 reached")
                        Log.i(TAG, "VERSION_RESPONSE $major.$minor")
                    } else if (id == AaFraming.MSG_ENCAPSULATED_SSL) {
                        logger.log("ENCAPSULATED_SSL (${frame.payload.size - 2}B of TLS record) -- rung 2 is this")
                    }
                }
            }
            logger.log("probe finished after $index frame(s)")
            Log.i(TAG, "finished after $index frames")
        } catch (e: Exception) {
            logger.log("failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "probe failed", e)
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            if (active === this) active = null
        }
    }

    private fun readFrame(input: DataInputStream): AaFraming.Frame {
        val header = ByteArray(2)
        input.readFully(header)
        val channelId = header[0].toInt() and 0xFF
        val flags = header[1].toInt() and 0xFF
        val frameType = flags and 0x03
        val messageType = flags and 0x04
        val encryption = flags and 0x08

        val sizeField = if (frameType == AaFraming.FRAME_FIRST) 6 else 2
        val sizeBytes = ByteArray(sizeField)
        input.readFully(sizeBytes)
        val frameSize = AaFraming.u16(sizeBytes, 0)
        val totalSize = if (sizeField == 6) AaFraming.u32(sizeBytes, 2) else frameSize

        val payload = ByteArray(frameSize)
        if (frameSize > 0) input.readFully(payload)

        return AaFraming.Frame(channelId, frameType, messageType, encryption, frameSize, totalSize, payload)
    }

    private fun logFrame(index: Int, f: AaFraming.Frame) {
        val enc = if (f.isPlain) "PLAIN" else "ENCRYPTED"
        val mtype = if (f.messageType == AaFraming.MESSAGE_CONTROL) "CONTROL" else "SPECIFIC"
        val named = if (f.channelId == AaFraming.CHANNEL_CONTROL && f.isPlain) {
            AaFraming.controlMessageId(f.payload)?.let { " ${AaFraming.controlMessageName(it)}" } ?: ""
        } else {
            ""
        }
        logger.log(
            "RX #$index ch=${AaFraming.channelName(f.channelId)}(${f.channelId}) " +
                "type=${AaFraming.frameTypeName(f.frameType)} $enc $mtype " +
                "size=${f.frameSize} total=${f.totalSize}$named"
        )
        if (f.payload.isNotEmpty()) logger.logBytes("RX", f.payload, minOf(f.payload.size, 512))
    }

    /** VERSION_RESPONSE body: uint16 BE message id, then major, then minor. */
    private fun versionFrom(payload: ByteArray): Pair<Int, Int> {
        if (payload.size < 6) return -1 to -1
        return AaFraming.u16(payload, 2) to AaFraming.u16(payload, 4)
    }
}
