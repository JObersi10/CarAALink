package dev.caraalink.aa

import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

/**
 * An established AA channel over TLS: reads/writes AA frames, reassembles
 * messages, and encrypts/decrypts payloads through the [SSLEngine].
 *
 * Framing facts (aasdk, cited in full in [AaFraming]): the 2-byte frame
 * header and the frame-size field stay **plaintext**; only the payload is
 * TLS-wrapped. `MessageOutStream::compoundFrame` encrypts just the payload,
 * and `Cryptor::encrypt` appends the wrapped bytes after the already-written
 * header/size.
 */
class AaTlsChannel(
    private val engine: SSLEngine,
    private val input: DataInputStream,
    private val output: OutputStream,
    private val logger: DiagnosticLogger,
) {

    data class Message(
        val channelId: Int,
        val messageType: Int,
        val payload: ByteArray,
        val frames: Int,
    )

    private var pending = ByteArray(0)
    private val appOut = ByteBuffer.allocate(engine.session.applicationBufferSize.coerceAtLeast(64 * 1024))
    private val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)

    /** Sends an ENCRYPTED control-channel message: payload is TLS-wrapped. */
    fun sendControlEncrypted(messageId: Int, body: ByteArray) =
        sendEncrypted(AaFraming.CHANNEL_CONTROL, AaFraming.MESSAGE_SPECIFIC, messageId, body)

    /**
     * Sends an ENCRYPTED message on a *service's own* channel (not the
     * CONTROL channel) using the `MessageType::CONTROL` frame flag (0x04).
     * This is how aasdk's per-service `*Service::sendChannelOpenResponse()`
     * replies to `CHANNEL_OPEN_REQUEST` -- e.g.
     * `SensorSourceService::sendChannelOpenResponse()`: ENCRYPTED, channel =
     * the service's own id, `MessageType::CONTROL`, msg id 8.
     */
    fun sendChannelControl(channelId: Int, messageId: Int, body: ByteArray) =
        sendEncrypted(channelId, AaFraming.MESSAGE_CONTROL, messageId, body)

    /**
     * Sends an ENCRYPTED, `MessageType::SPECIFIC` message on a service's own
     * channel -- how aasdk's media/sensor services answer `MEDIA_MESSAGE_*`
     * and `SENSOR_MESSAGE_*` requests (e.g.
     * `VideoMediaSinkService::sendChannelSetupResponse()`,
     * `SensorSourceService::sendSensorStartResponse()`).
     */
    fun sendChannelSpecific(channelId: Int, messageId: Int, body: ByteArray) =
        sendEncrypted(channelId, AaFraming.MESSAGE_SPECIFIC, messageId, body)

    private fun sendEncrypted(channelId: Int, messageType: Int, messageId: Int, body: ByteArray) {
        val plain = ByteArray(2 + body.size)
        AaFraming.putU16(plain, 0, messageId)
        body.copyInto(plain, 2)
        val cipher = wrap(plain)
        val frame = AaFraming.frame(channelId, AaFraming.FRAME_BULK, AaFraming.ENCRYPTION_ENCRYPTED, messageType, cipher)
        logger.log(
            "TX ${AaFraming.controlMessageName(messageId)} on ${AaFraming.channelName(channelId)} " +
                "encrypted plain=${plain.size}B cipher=${cipher.size}B",
        )
        output.write(frame)
        output.flush()
    }

    /** Sends a PLAINTEXT control-channel message. */
    fun sendControlPlain(messageId: Int, body: ByteArray) {
        val frame = AaFraming.controlMessage(messageId, body)
        logger.log("TX ${AaFraming.controlMessageName(messageId)} plain ${frame.size}B")
        output.write(frame)
        output.flush()
    }

    /** Reads frames until one full message has been assembled and decrypted. */
    fun receiveMessage(): Message {
        var channelId = -1
        var messageType = AaFraming.MESSAGE_SPECIFIC
        var expectedTotal = -1
        var frames = 0
        val acc = ByteArrayOutputStream()

        while (true) {
            val frame = readFrame()
            frames++
            if (channelId < 0) {
                channelId = frame.channelId
                messageType = frame.messageType
            }
            if (frame.isFirst) expectedTotal = frame.totalSize

            val plain = if (frame.encryption == AaFraming.ENCRYPTION_ENCRYPTED) {
                unwrap(frame.payload)
            } else {
                frame.payload
            }
            acc.write(plain)

            logger.log(
                "RX frame ch=${AaFraming.channelName(frame.channelId)} " +
                    "type=${AaFraming.frameTypeName(frame.frameType)} " +
                    "${if (frame.isPlain) "PLAIN" else "ENCRYPTED"} " +
                    "size=${frame.frameSize} encrypted->${plain.size}"
            )

            // A message ends at its LAST frame. Do NOT compare accumulated
            // plaintext length against totalSize on encrypted frames: totalSize
            // counts ciphertext, so the plaintext is always shorter (TLS
            // overhead) and the loop would block forever waiting for bytes that
            // already arrived.
            if (frame.isLast) break
            if (frames > 512) error("message did not terminate after $frames frames")
        }

        return Message(channelId, messageType, acc.toByteArray(), frames)
    }

    /** Runs one SSLEngine wrap and returns the TLS record bytes. */
    private fun wrap(plain: ByteArray): ByteArray {
        netOut.clear()
        val result = engine.wrap(ByteBuffer.wrap(plain), netOut)
        check(result.status != SSLEngineResult.Status.BUFFER_OVERFLOW) { "wrap overflow" }
        netOut.flip()
        val record = ByteArray(netOut.remaining())
        netOut.get(record)
        return record
    }

    /**
     * Feeds TLS record bytes in and returns whatever plaintext comes out.
     * Unconsumed input is retained in [pending] across calls — dropping it
     * deadlocks the stream (that bug cost us a live session, see roadmap).
     */
    private fun unwrap(record: ByteArray): ByteArray {
        pending += record
        val out = ByteArrayOutputStream()
        while (pending.isNotEmpty()) {
            val buf = ByteBuffer.wrap(pending)
            appOut.clear()
            val result = engine.unwrap(buf, appOut)
            val consumed = buf.position()
            if (consumed > 0) pending = pending.copyOfRange(consumed, pending.size)
            appOut.flip()
            if (appOut.remaining() > 0) {
                val chunk = ByteArray(appOut.remaining())
                appOut.get(chunk)
                out.write(chunk)
            }
            when (result.status) {
                SSLEngineResult.Status.OK -> {
                    if (consumed == 0) break
                    if (result.bytesProduced() == 0 && pending.isEmpty()) break
                }
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> break
                SSLEngineResult.Status.CLOSED -> break
                SSLEngineResult.Status.BUFFER_OVERFLOW ->
                    throw IllegalStateException("unwrap overflow, need a larger app buffer")
            }
        }
        return out.toByteArray()
    }

    private fun readFrame(): AaFraming.Frame {
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
}
