package dev.caraalink.link

import android.os.Build
import dev.caraalink.diagnostics.DiagnosticLogger
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PXC control protocol on tcp:10922 (claude/roadmap.md "PXC + mirror
 * protocol"): 16-byte LE head `cmd, totalLen, cmd^totalLen, extra` + body.
 * The first head on a stream opens a channel and is echoed with cmd+1; later
 * requests get reply cmd+1 (except the no-reply set). Only CLIENT_INFO gets a
 * real body; everything else is acknowledged empty and logged so the next
 * live test shows what the head unit expects.
 */
class PxcChannel(
    private val logger: DiagnosticLogger,
    private val onClientInfo: (JSONObject) -> Unit,
    /** Body for the reply to a car request, or null for an empty ack. */
    private val replyBody: (cmd: Int) -> ByteArray? = { null },
) : AdbTransport.StreamHandler {

    private val buffers = HashMap<Int, ByteArray>()
    private val channelOf = HashMap<Int, Int>()

    override fun onOpen(stream: AdbTransport.Stream) {
        buffers[stream.localId] = ByteArray(0)
    }

    override fun onClose(stream: AdbTransport.Stream) {
        buffers.remove(stream.localId)
        if (channelOf.remove(stream.localId) == P2C_CHANNEL) stopHeartbeat(stream)
    }

    private var heartbeat: Thread? = null
    @Volatile private var p2cStream: AdbTransport.Stream? = null

    /** Sends a phone-to-car request on the P2C channel; false if it isn't open. */
    fun sendP2C(cmd: Int, body: ByteArray): Boolean {
        val s = p2cStream?.takeIf { !it.closed } ?: return false
        logger.log("PXC P2C send 0x${hex(cmd)} len=${body.size}" + preview(body))
        s.write(head(cmd, body, 0))
        return true
    }

    /**
     * Channel 0x20000 is phone-to-car (CarbitLink's PXCForCar-P2C): the phone
     * sends requests there, including the 0x70000000 keep-alive the car also
     * sends us. Without it the car drops the session ~5 s in.
     */
    private fun startHeartbeat(stream: AdbTransport.Stream) {
        heartbeat?.interrupt()
        heartbeat = Thread({
            try {
                while (!stream.closed) {
                    stream.write(head(HEARTBEAT, ByteArray(0), 0))
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
            }
        }, "PxcHeartbeat").also { it.isDaemon = true; it.start() }
    }

    private fun stopHeartbeat(stream: AdbTransport.Stream) {
        heartbeat?.interrupt()
        heartbeat = null
    }

    override fun onData(stream: AdbTransport.Stream, data: ByteArray) {
        var buf = (buffers[stream.localId] ?: ByteArray(0)) + data
        while (buf.size >= 16) {
            val bb = ByteBuffer.wrap(buf, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.getInt(0)
            val total = bb.getInt(4)
            if (cmd xor total != bb.getInt(8) || total < 16) {
                logger.log("PXC bad head on ${stream.localId}, dropping ${buf.size} bytes")
                buf = ByteArray(0)
                break
            }
            if (buf.size < total) break
            val body = buf.copyOfRange(16, total)
            buf = buf.copyOfRange(total, buf.size)
            handle(stream, cmd, bb.getInt(12), body)
        }
        buffers[stream.localId] = buf
    }

    private fun handle(stream: AdbTransport.Stream, cmd: Int, extra: Int, body: ByteArray) {
        if (channelOf[stream.localId] == null) {
            channelOf[stream.localId] = cmd
            logger.log("PXC channel open 0x${hex(cmd)} on stream ${stream.localId}")
            stream.write(head(cmd + 1, ByteArray(0), extra))
            if (cmd == P2C_CHANNEL) {
                p2cStream = stream
                startHeartbeat(stream)
            }
            return
        }
        if (channelOf[stream.localId] == P2C_CHANNEL) {
            // Replies to our requests; never answer these.
            if (cmd != HEARTBEAT + 1) logger.log("PXC P2C reply 0x${hex(cmd)} len=${body.size}" + preview(body))
            return
        }
        if (cmd != HEARTBEAT && cmd != 0x10750 && cmd != 0x10430) {
            logger.log("PXC C2P 0x${hex(cmd)} len=${body.size}" + preview(body))
        }
        when {
            cmd == CLIENT_INFO -> {
                runCatching { onClientInfo(JSONObject(String(body, Charsets.UTF_8))) }
                stream.write(head(cmd + 1, clientInfoReply(), 0))
            }
            needsReply(cmd) -> stream.write(head(cmd + 1, replyBody(cmd) ?: ByteArray(0), 0))
        }
    }

    /** Key names from CarbitLink's CLIENT_INFO reply builder; RSA fields omitted. */
    private fun clientInfoReply(): ByteArray = JSONObject()
        .put("pxcVersion", "1.0.2")
        .put("phoneUUID", "caraalink-" + Build.MODEL.replace(' ', '_'))
        .put("phoneBrand", Build.BRAND)
        .put("phoneModel", Build.MODEL)
        .put("phoneOsVersion", Build.VERSION.SDK_INT.toString())
        .put("phoneOs", "android")
        .put("token", 0)
        .put("supportH264IFrame", true)
        .toString().toByteArray(Charsets.UTF_8)

    private fun head(cmd: Int, body: ByteArray, extra: Int): ByteArray {
        val total = 16 + body.size
        return ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd).putInt(total).putInt(cmd xor total).putInt(extra).put(body).array()
    }

    private fun needsReply(cmd: Int) = !(cmd == 0x40010 || cmd == 0x30030 || cmd == 0x30020 ||
        cmd == 0x30040 || cmd == 0x102B0 || (cmd and 0xFF000000.toInt()) == 0x60000000)

    private fun preview(body: ByteArray): String {
        if (body.isEmpty()) return ""
        val text = String(body, Charsets.UTF_8)
        return if (text.all { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 }) {
            " " + text.replace(Regex("\\s+"), " ").take(300)
        } else {
            " hex=" + body.take(48).joinToString("") { "%02X".format(it) }
        }
    }

    private fun hex(v: Int) = "%08X".format(v)

    companion object {
        const val CLIENT_INFO = 0x10010
        const val P2C_CHANNEL = 0x20000
        const val HEARTBEAT = 0x70000000
    }
}
