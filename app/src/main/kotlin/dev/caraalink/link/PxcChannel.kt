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
) : AdbTransport.StreamHandler {

    private val buffers = HashMap<Int, ByteArray>()
    private val channelOf = HashMap<Int, Int>()

    override fun onOpen(stream: AdbTransport.Stream) {
        buffers[stream.localId] = ByteArray(0)
    }

    override fun onClose(stream: AdbTransport.Stream) {
        buffers.remove(stream.localId)
        channelOf.remove(stream.localId)
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
            return
        }
        logger.log("PXC C2P 0x${hex(cmd)} len=${body.size}" + preview(body))
        when {
            cmd == CLIENT_INFO -> {
                runCatching { onClientInfo(JSONObject(String(body, Charsets.UTF_8))) }
                stream.write(head(cmd + 1, clientInfoReply(), 0))
            }
            needsReply(cmd) -> stream.write(head(cmd + 1, ByteArray(0), 0))
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
    }
}
