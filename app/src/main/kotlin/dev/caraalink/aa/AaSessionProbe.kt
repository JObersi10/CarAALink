package dev.caraalink.aa

import android.content.Context
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

/**
 * Rungs 1-2 of the Android Auto receiver: plaintext version exchange, then the
 * TLS handshake carried *inside* PLAIN control messages.
 *
 * Rung 1 is confirmed on hardware (S25 Ultra, AA 18.0.664244):
 *
 * ```
 * TX 00 03 00 06 00 01 00 01 00 06
 * RX 00 02 00 01 00 07 00 00   -> VERSION_RESPONSE 1.7, status 0
 * ```
 *
 * Rung 2 follows `ControlServiceChannel::sendHandshake()` /
 * `receive()` in aasdk: the head unit is the TLS *client*, and each TLS
 * record is shipped as a PLAIN control message with id
 * `MESSAGE_ENCAPSULATED_SSL` (3). That is why raw TLS on the socket never
 * worked — the socket only ever carries AA frames.
 *
 * Java has no socket to hand to JSSE here, so this uses [SSLEngine], which
 * does the same job over memory buffers: wrap -> send as an encapsulated-SSL
 * message, receive -> unwrap. Nothing above TLS is interpreted.
 */
class AaSessionProbe(
    private val context: Context,
    private val config: Config = Config(),
) {

    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 5277,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 15_000,
        // Discovery + audio focus + one CHANNEL_OPEN_REQUEST per registered
        // service already uses ~10 messages before any media setup happens;
        // the old default of 10 made the probe hang up on itself (closing the
        // socket in its own `finally`) right as the real session began --
        // gearhead's reader thread logged "end of stream received" at that
        // exact point, which looked like a protocol rejection but wasn't one.
        val maxMessages: Int = 2_000,
        /** Overrides the version we advertise; null uses AaFraming.PROTOCOL_MAJOR/MINOR. */
        val versionMajor: Int? = null,
        val versionMinor: Int? = null,
        val maxAttempts: Int = 3,
        val retryDelayMs: Long = 2_000,
    )

    companion object {
        private const val TAG = "AaSessionProbe"

        @Volatile
        var active: AaSessionProbe? = null
            private set
    }

    private val logger = DiagnosticLogger(context, "aa-session")
    private val running = AtomicBoolean(false)
    private var socket: Socket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start: already running")
            return
        }
        logger.log("=== AA session probe ${config.host}:${config.port}")
        Log.i(TAG, "start")
        worker = Thread({ run() }, "AaSessionProbe").apply { isDaemon = true }
        active = this
        worker!!.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        logger.log("=== session probe stopped")
        runCatching { socket?.close() }
        socket = null
        worker?.interrupt()
        worker = null
        if (active === this) active = null
    }

    private fun run() {
        // The gearhead's head unit server appears to service one connection at
        // a time and goes deaf to the next one after a session dies uncleanly
        // (observed: attempt at 16:54:18 answered instantly, the one at
        // 16:55:17 got no reply at all). Retry on a fresh socket rather than
        // reporting a protocol failure that is really a stale-session artefact.
        var attempt = 0
        try {
            while (running.get() && attempt < config.maxAttempts) {
                attempt++
                logger.log("--- attempt $attempt/${config.maxAttempts}")
                if (attemptOnce()) return
                if (attempt < config.maxAttempts) Thread.sleep(config.retryDelayMs)
            }
            logger.log("gave up after $attempt attempt(s)")
        } finally {
            running.set(false)
            runCatching { socket?.close() }
            socket = null
            if (active === this) active = null
        }
    }

    /** @return true if a full session ran (handshake + observation) rather than failing. */
    private fun attemptOnce(): Boolean {
        return try {
            val identity = AaIdentity.load(context)
            val s = Socket().apply {
                connect(InetSocketAddress(config.host, config.port), config.connectTimeoutMs)
                soTimeout = config.readTimeoutMs
            }
            socket = s
            val input = DataInputStream(s.getInputStream())
            val output = s.getOutputStream()
            logger.log("tcp connected ${s.localSocketAddress} -> ${s.remoteSocketAddress}")

            // --- rung 1: version exchange ---
            val request = AaFraming.versionRequest(
                major = config.versionMajor ?: AaFraming.PROTOCOL_MAJOR,
                minor = config.versionMinor ?: AaFraming.PROTOCOL_MINOR,
            )
            logger.logBytes("TX", request, request.size)
            output.write(request); output.flush()

            val response = readFrame(input)
            logFrame(1, response)
            val versionId = AaFraming.controlMessageId(response.payload)
            if (versionId != AaFraming.MSG_VERSION_RESPONSE) {
                logger.log("unexpected first reply ${AaFraming.controlMessageName(versionId ?: -1)}")
                return false
            }
            val major = AaFraming.u16(response.payload, 2)
            val minor = AaFraming.u16(response.payload, 4)
            val status = if (response.payload.size >= 8) AaFraming.u16(response.payload, 6) else -1
            logger.log("VERSION_RESPONSE $major.$minor status=$status")

            // --- rung 2: TLS inside encapsulated-SSL control messages ---
            val engine = identity.sslContextLeafOnly.createSSLEngine(config.host, config.port).apply {
                useClientMode = true
                // Reference app pins TLSv1.2 only (`enabled=[TLSv1.2]`); offering
                // 1.3 as well made our ClientHello 233B vs their 138B. Match it.
                enabledProtocols = arrayOf("TLSv1.2")
                beginHandshake()
            }
            logger.log("TLS engine: client mode, ${engine.session.protocol} max=${engine.session.packetBufferSize}")
            handshake(engine, input, output)
            if (!running.get()) return true

            val session = engine.session
            logger.log("TLS ESTABLISHED ${session.protocol} ${session.cipherSuite}")
            Log.i(TAG, "TLS established ${session.protocol} ${session.cipherSuite}")
            logPeer(engine)

            // --- rung 3: control channel is live, wait for the phone ---
            //
            // Direction corrected against aasdk: the *phone* sends
            // SERVICE_DISCOVERY_REQUEST and the head unit answers with
            // SERVICE_DISCOVERY_RESPONSE (ENCRYPTED). So there is nothing to
            // send unprompted here -- we listen and decode.
            val channel = AaTlsChannel(engine, input, output, logger)

            // AUTH_COMPLETE (PLAIN) immediately after the handshake.
            // openauto's AndroidAutoEntity::onHandshake() does exactly this:
            //   if (cryptor_->doHandshake()) { AuthResponse{status=STATUS_SUCCESS};
            //     controlServiceChannel_->sendAuthComplete(...); }
            // ControlServiceChannel never *handles* AUTH_COMPLETE, so it is a
            // head-unit -> phone step only, and it was the one we were missing.
            val authResponse = AaProtobuf.Writer()
                .int(1, 0) // AuthResponse.status = MessageStatus::STATUS_SUCCESS
                .build()
            logger.log("TX AUTH_COMPLETE status=0")
            channel.sendControlPlain(AaFraming.MSG_AUTH_COMPLETE, authResponse)

            var index = 0
            while (running.get() && index < config.maxMessages) {
                index++
                val message = channel.receiveMessage()
                if (message.channelId != AaFraming.CHANNEL_CONTROL) {
                    val chId = AaFraming.controlMessageId(message.payload)
                    val chBody = if (message.payload.size >= 2) message.payload.copyOfRange(2, message.payload.size) else ByteArray(0)
                    logger.log(
                        "MESSAGE on ${AaFraming.channelName(message.channelId)} " +
                            "${AaFraming.controlMessageName(chId ?: -1)} (${chBody.size}B)",
                    )
                    if (chId == AaFraming.MSG_CHANNEL_OPEN_REQUEST) {
                        // ChannelOpenRequest{priority=1 sint32, service_id=2 int32}. We
                        // accept every channel the phone asks for -- aasdk's per-service
                        // handlers (e.g. SensorSourceService::sendChannelOpenResponse())
                        // always reply ENCRYPTED, MessageType::CONTROL, msg id 8,
                        // ChannelOpenResponse{status=0 (STATUS_SUCCESS)}, on the
                        // service's own channel, not CONTROL.
                        val fields = AaProtobuf.parse(chBody)
                        val priority = fields.firstOrNull { it.number == 1 }?.varint
                        val serviceId = fields.firstOrNull { it.number == 2 }?.varint
                        logger.log(
                            "RX CHANNEL_OPEN_REQUEST on ${AaFraming.channelName(message.channelId)} " +
                                "priority=$priority service_id=$serviceId",
                        )
                        val response = AaProtobuf.Writer().int(1, 0).build() // status = STATUS_SUCCESS
                        channel.sendChannelControl(message.channelId, AaFraming.MSG_CHANNEL_OPEN_RESPONSE, response)
                    }
                    continue
                }
                val id = AaFraming.controlMessageId(message.payload)
                val body = message.payload.copyOfRange(2, message.payload.size)
                logger.log("MESSAGE ${AaFraming.controlMessageName(id ?: -1)} (${body.size}B, ${message.frames} frame(s))")
                logger.logBytes("RXBODY", body, minOf(body.size, 256))
                when (id) {
                    AaFraming.MSG_SERVICE_DISCOVERY_REQUEST -> {
                        decodeDiscoveryRequest(body)
                        logger.log("rung 3 reached -- phone asked for service discovery")
                        val response = AaServiceDiscovery.build()
                        logger.log("TX SERVICE_DISCOVERY_RESPONSE ${response.size}B (${AaServiceDiscovery.describe()})")
                        logger.logBytes("TXBODY", response, response.size)
                        channel.sendControlEncrypted(AaFraming.MSG_SERVICE_DISCOVERY_RESPONSE, response)
                    }
                    AaFraming.MSG_PING_REQUEST -> channel.sendControlEncrypted(AaFraming.MSG_PING_RESPONSE, body)
                    AaFraming.MSG_AUDIO_FOCUS_REQUEST -> {
                        // AudioFocusRequest.audio_focus_type = 1 (AUDIO_FOCUS_RELEASE = 4).
                        // openauto's AndroidAutoEntity::onAudioFocusRequest(): release -> LOSS(3),
                        // anything else -> GAIN(1). AudioFocusNotification.focus_state = 1.
                        val requestType = AaProtobuf.parse(body).firstOrNull { it.number == 1 }?.varint
                        val focusState = if (requestType == 4L) 3 else 1
                        logger.log("RX AUDIO_FOCUS_REQUEST type=$requestType -> focus_state=$focusState")
                        val notification = AaProtobuf.Writer().int(1, focusState).build()
                        channel.sendControlEncrypted(AaFraming.MSG_AUDIO_FOCUS_NOTIFICATION, notification)
                    }
                }
            }
            true
        } catch (e: Exception) {
            logger.log("failed: ${e.javaClass.simpleName}: ${e.message}")
            Log.w(TAG, "session failed", e)
            false
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    /**
     * Drives [SSLEngine] over AA frames. Each `wrap()` output becomes the body
     * of a PLAIN `MESSAGE_ENCAPSULATED_SSL` message; each such message we
     * receive is fed back into `unwrap()`.
     *
     * Important detail: one AA message can carry several TLS records, and
     * `unwrap` may stop mid-buffer. Leftover bytes are kept in [pending] and
     * fed back in before reading another frame — discarding them deadlocks
     * the handshake (first hardware run did exactly that: ServerHello
     * arrived, unwrap said NEED_UNWRAP, and we blocked waiting for data we
     * had already been given).
     */
    private fun handshake(engine: SSLEngine, input: DataInputStream, output: java.io.OutputStream) {
        val app = ByteBuffer.allocate(engine.session.applicationBufferSize)
        val netOut = ByteBuffer.allocate(engine.session.packetBufferSize)
        val empty = ByteBuffer.allocate(0)
        var pending = ByteArray(0)

        fun send(record: ByteArray) {
            if (record.isEmpty()) return
            val message = AaFraming.controlMessage(AaFraming.MSG_ENCAPSULATED_SSL, record)
            logger.log("TX ENCAPSULATED_SSL (${record.size}B)")
            logger.logBytes("TX", record, minOf(record.size, 64))
            output.write(message)
            output.flush()
        }

        /** Next encapsulated-SSL record body, skipping anything else. */
        fun nextRecord(): ByteArray {
            while (true) {
                val frame = readFrame(input)
                val id = AaFraming.controlMessageId(frame.payload)
                if (id == AaFraming.MSG_ENCAPSULATED_SSL) {
                    val record = frame.payload.copyOfRange(2, frame.payload.size)
                    logger.log("RX ENCAPSULATED_SSL (${record.size}B)")
                    return record
                }
                logger.log("skipping ${AaFraming.controlMessageName(id ?: -1)} during handshake")
                logFrame(-1, frame)
            }
        }

        var rounds = 0
        while (running.get() && rounds++ < 120) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> engine.delegatedTask?.run()

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(empty, netOut)
                    netOut.flip()
                    val record = ByteArray(netOut.remaining())
                    netOut.get(record)
                    logger.log("wrap ${result.status} -> ${record.size}B")
                    send(record)
                }

                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    var progressed = false
                    while (true) {
                        if (pending.isEmpty()) {
                            pending = nextRecord()
                        }
                        val buf = ByteBuffer.wrap(pending)
                        app.clear()
                        val result = engine.unwrap(buf, app)
                        val consumed = buf.position()
                        if (consumed > 0) {
                            pending = pending.copyOfRange(consumed, pending.size)
                            progressed = true
                        }
                        logger.log(
                            "unwrap ${result.status}/${result.handshakeStatus} " +
                                "consumed=$consumed produced=${result.bytesProduced()} left=${pending.size}"
                        )
                        if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                            pending += nextRecord()
                            continue
                        }
                        if (result.status == SSLEngineResult.Status.CLOSED) {
                            throw SSLException("engine closed during handshake")
                        }
                        if (engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) break
                        if (consumed == 0 && pending.isNotEmpty()) break
                    }
                    if (!progressed && pending.isEmpty()) {
                        throw SSLException("handshake stalled with nothing buffered")
                    }
                }

                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return

                null -> return
            }
        }
        throw SSLException("handshake did not complete in $rounds rounds")
    }

    /** ServiceDiscoveryRequest.proto: label_text=4, device_name=5, phone_info=6. */
    private fun decodeDiscoveryRequest(body: ByteArray) {
        val fields = runCatching { AaProtobuf.parse(body) }.getOrElse {
            logger.log("  could not parse discovery request: ${it.message}")
            return
        }
        for (f in fields) {
            when (f.number) {
                4 -> logger.log("  label_text='${AaProtobuf.utf8(f)}'")
                5 -> logger.log("  device_name='${AaProtobuf.utf8(f)}'")
                6 -> logger.log("  phone_info (${f.bytes?.size ?: 0}B)")
                1, 2, 3 -> logger.log("  icon field ${f.number} (${f.bytes?.size ?: 0}B)")
            }
        }
    }

    /** ServiceDiscoveryResponse.proto: field 1 = repeated service.Service. */
    private fun decodeServiceDiscovery(body: ByteArray) {
        val fields = runCatching { AaProtobuf.parse(body) }.getOrElse {
            logger.log("  could not parse discovery response: ${it.message}")
            return
        }
        logger.log("SERVICE_DISCOVERY_RESPONSE ${body.size}B, ${fields.count { it.number == 1 }} channel(s)")
        for (f in fields) {
            if (f.number != 1 || f.bytes == null) continue
            val channel = runCatching { AaProtobuf.parse(f.bytes) }.getOrNull() ?: continue
            val id = channel.firstOrNull { it.number == 1 }?.varint ?: -1
            val kinds = channel.filter { it.number in 2..14 }
                .joinToString(",") { serviceName(it.number.toInt()) }
            logger.log("  channel id=$id other_fields=[$kinds]")
        }
        val displayName = fields.firstOrNull { it.number == 14 }?.let { AaProtobuf.utf8(it) }
        if (!displayName.isNullOrBlank()) logger.log("  display_name=$displayName")
        logger.log("rung 3 reached -- channel list above")
        Log.i(TAG, "service discovery decoded")
    }

    /** aap_protobuf/service/Service.proto field numbers. */
    private fun serviceName(field: Int): String = when (field) {
        2 -> "sensor_source"
        3 -> "media_sink"
        4 -> "input_source"
        5 -> "media_source"
        6 -> "bluetooth"
        7 -> "radio"
        8 -> "navigation_status"
        9 -> "media_playback"
        10 -> "phone_status"
        11 -> "media_browser"
        12 -> "vendor_extension"
        13 -> "generic_notification"
        14 -> "wifi_projection"
        else -> "field$field"
    }

    private fun logPeer(engine: SSLEngine) {
        val session = engine.session
        val peer = runCatching { session.peerCertificates }.getOrNull()
        if (peer.isNullOrEmpty()) {
            logger.log("  peer: no certificate (phone does not authenticate us at TLS layer)")
        } else {
            peer.forEachIndexed { i, cert ->
                (cert as? X509Certificate)?.let {
                    logger.log("  peer[$i] '${it.subjectX500Principal.name}' sha256=${AaIdentity.fingerprint(it)}")
                }
            }
        }
        runCatching { session.localCertificates }.getOrNull()?.forEachIndexed { i, cert ->
            (cert as? X509Certificate)?.let {
                logger.log("  local[$i] '${it.subjectX500Principal.name}'")
            }
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
        } else ""
        logger.log(
            "RX #$index ch=${AaFraming.channelName(f.channelId)}(${f.channelId}) " +
                "type=${AaFraming.frameTypeName(f.frameType)} $enc $mtype size=${f.frameSize}$named"
        )
        if (f.payload.isNotEmpty()) logger.logBytes("RX", f.payload, minOf(f.payload.size, 256))
    }
}
