package dev.caraalink.aa

import android.content.Context
import android.util.Log
import dev.caraalink.diagnostics.DiagnosticLogger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * TLS endpoint that speaks Android Auto's gearhead handshake using the
 * head-unit identity from [AaIdentity].
 *
 * This is the piece that was previously missing: the app can now present a
 * Google-Automotive-Link-issued receiver certificate instead of a self-signed
 * one, which is the specific thing `claude/handoff.md` recorded as blocking a
 * car-side AA session ("the connecting device checks the certificate chain,
 * and a self-signed one fails before any stream opens").
 *
 * ## Roles
 *
 * The head unit is the TLS **client**: aasdk builds its context with
 * `TLS_client_method()` and calls `SSL_set_connect_state()`, then presents the
 * head-unit certificate as a *client* certificate, with the phone as TLS
 * server (`SSL_VERIFY_NONE` on its side — it does not verify us at the TLS
 * layer). A first live run agrees: dialling 5277 and trying to be the TLS
 * server meant sitting on a ClientHello that never arrived, and the phone
 * dropped the socket after ~106 s.
 *
 * So [TlsRole.AUTO] tries [TlsRole.CLIENT] first and falls back to
 * [TlsRole.SERVER]. TLS starts at the first byte — there is no plaintext
 * version exchange before it; the AA messenger runs inside the TLS channel.
 *
 * Nothing above TLS is implemented or invented: past the handshake this only
 * observes and dumps bytes (same discipline as
 * [dev.caraalink.diagnostics.AdbHandshakeProbe]).
 */
class AaTlsReceiver(
    private val context: Context,
    private val config: Config = Config(),
) {

    enum class Mode {
        /** Connect out to [Config.host]:[Config.port] — the self-hosted case, phone listens on 5277. */
        DIAL,

        /** Bind [Config.host]:[Config.port] and accept — the openauto-shaped case. */
        LISTEN,
    }

    enum class TlsRole { SERVER, CLIENT, AUTO }

    data class Config(
        val mode: Mode = Mode.DIAL,
        val host: String = "127.0.0.1",
        val port: Int = 5277,
        val tlsRole: TlsRole = TlsRole.AUTO,
        val connectTimeoutMs: Int = 5_000,
        /** How long to wait for the TLS handshake before declaring the role wrong. */
        val handshakeTimeoutMs: Int = 15_000,
        /** Cap on how much payload gets hex-dumped into the log file. */
        val maxLoggedPayloadBytes: Int = 64 * 1024,
    )

    companion object {
        private const val TAG = "AaTlsReceiver"

        /** The instance currently running, if any — lets the AA tile drive the same receiver. */
        @Volatile
        var active: AaTlsReceiver? = null
            private set
    }

    /** UI hook so the car screen can narrate what the receiver is doing. */
    @Volatile
    var statusSink: ((String) -> Unit)? = null

    private val logger = DiagnosticLogger(context, "aa-tls")
    private val running = AtomicBoolean(false)
    private val socketLock = Any()

    private var plainSocket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var serverSocket: SSLServerSocket? = null
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "start: already running")
            return
        }

        val identity = try {
            AaIdentity.load(context)
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "start: no AA identity", e)
            logger.log("ERROR identity unavailable: ${e.message}")
            return
        }

        Log.i(TAG, "start mode=${config.mode} role=${config.tlsRole} ${config.host}:${config.port}")
        logger.log("=== AA TLS receiver: mode=${config.mode} role=${config.tlsRole} ${config.host}:${config.port}")
        statusSink?.invoke("AA: connecting to ${config.host}:${config.port}")
        logger.log(identity.describe())

        val t = Thread({ run(config, identity) }, "AaTlsReceiver").apply { isDaemon = true }
        worker = t
        synchronized(socketLock) { active = this }
        t.start()
    }

    /** Safe to call from any thread, including when nothing is running. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        Log.i(TAG, "stop")
        logger.log("=== receiver stopped")
        synchronized(socketLock) {
            runCatching { tlsSocket?.close() }
            runCatching { plainSocket?.close() }
            runCatching { serverSocket?.close() }
            tlsSocket = null
            plainSocket = null
            serverSocket = null
            if (active === this) active = null
        }
        worker?.interrupt()
        worker = null
    }

    private fun run(cfg: Config, identity: AaIdentity.Loaded) {
        when (cfg.mode) {
            Mode.DIAL -> runDial(cfg, identity)
            Mode.LISTEN -> runListen(cfg, identity)
        }
        // Only clear the flag if this is still the live receiver; stop() may
        // have already done it.
        if (Thread.currentThread() === worker) {
            running.set(false)
            synchronized(socketLock) { if (active === this) active = null }
        }
    }

    private fun runDial(cfg: Config, identity: AaIdentity.Loaded) {
        val roles = when (cfg.tlsRole) {
            TlsRole.SERVER -> listOf(TlsRole.SERVER)
            TlsRole.CLIENT -> listOf(TlsRole.CLIENT)
            // CLIENT first, on evidence: aasdk builds its context with
            // TLS_client_method() and SSL_set_connect_state(), i.e. the head
            // unit opens the TLS handshake and presents the cert as a *client*
            // certificate to the phone, which is the TLS server on 5277. A
            // first live run confirmed the inverse: as SERVER we sat waiting
            // for a ClientHello that never came and the phone dropped the
            // socket after ~106 s. Keep SERVER as the fallback in case that
            // differs on another gearhead build.
            TlsRole.AUTO -> listOf(TlsRole.CLIENT, TlsRole.SERVER)
        }

        for ((index, role) in roles.withIndex()) {
            if (!running.get()) return
            if (index > 0) {
                logger.log("--- retrying with the other TLS role ($role)")
                Thread.sleep(500)
            }
            if (attemptDial(cfg, identity, role)) return
        }
        logger.log("ERROR no TLS role completed a handshake against ${cfg.host}:${cfg.port}")
        statusSink?.invoke("AA: no handshake with ${cfg.host}:${cfg.port}")
        Log.e(TAG, "dial: every role failed")
    }

    /** @return true if a TLS session was established and the observation loop ran. */
    private fun attemptDial(cfg: Config, identity: AaIdentity.Loaded, role: TlsRole): Boolean {
        var raw: Socket? = null
        try {
            logger.log("dial ${cfg.host}:${cfg.port} (tcp)")
            raw = Socket().apply { connect(InetSocketAddress(cfg.host, cfg.port), cfg.connectTimeoutMs) }
            synchronized(socketLock) { plainSocket = raw }
            logger.log("tcp connected local=${raw.localSocketAddress} remote=${raw.remoteSocketAddress}")

            val ssl = identity.sslContext.socketFactory
                .createSocket(raw, cfg.host, cfg.port, true) as SSLSocket
            ssl.useClientMode = role == TlsRole.CLIENT
            ssl.enabledProtocols = usableProtocols(ssl)
            synchronized(socketLock) { tlsSocket = ssl; plainSocket = null; raw = null }

            logger.log("starting TLS handshake as ${role.name} (protocols=${ssl.enabledProtocols.joinToString(",")})")
            ssl.soTimeout = cfg.handshakeTimeoutMs
            ssl.startHandshake()
            ssl.soTimeout = 0
            logSession(ssl, role)

            observe(ssl)
            return true
        } catch (e: Exception) {
            logger.log("handshake/connect failed as ${role.name}: ${describe(e)}")
            Log.w(TAG, "dial role=$role failed", e)
            synchronized(socketLock) {
                runCatching { tlsSocket?.close() }
                runCatching { raw?.close() }
                runCatching { plainSocket?.close() }
                tlsSocket = null
                plainSocket = null
            }
            return false
        }
    }

    private fun runListen(cfg: Config, identity: AaIdentity.Loaded) {
        try {
            val server = identity.sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            server.reuseAddress = true
            server.bind(InetSocketAddress(cfg.host, cfg.port), 4)
            synchronized(socketLock) { serverSocket = server }
            logger.log("listening ${cfg.host}:${cfg.port} (TLS server)")
            Log.i(TAG, "listening ${cfg.host}:${cfg.port}")

            while (running.get()) {
                val accepted = try {
                    // SSLServerSocket inherits accept(): Socket, but always yields an SSLSocket.
                    server.accept() as SSLSocket
                } catch (e: IOException) {
                    if (running.get()) logger.log("accept failed: ${describe(e)}")
                    break
                }
                logger.log("accepted from ${accepted.remoteSocketAddress}")
                Thread({ handleAccepted(accepted) }, "AaTlsReceiver-conn").apply { isDaemon = true }.start()
            }
        } catch (e: Exception) {
            logger.log("listen failed: ${describe(e)}")
            Log.e(TAG, "listen failed", e)
        } finally {
            synchronized(socketLock) {
                runCatching { serverSocket?.close() }
                serverSocket = null
            }
        }
    }

    private fun handleAccepted(ssl: SSLSocket) {
        try {
            ssl.useClientMode = false
            ssl.enabledProtocols = usableProtocols(ssl)
            logger.log("starting TLS handshake as SERVER (protocols=${ssl.enabledProtocols.joinToString(",")})")
            ssl.startHandshake()
            logSession(ssl, TlsRole.SERVER)
            observe(ssl)
        } catch (e: Exception) {
            logger.log("accepted-connection handshake failed: ${describe(e)}")
        } finally {
            runCatching { ssl.close() }
        }
    }

    private fun logSession(ssl: SSLSocket, role: TlsRole) {
        val session = ssl.session
        logger.log("TLS ESTABLISHED role=${role.name} protocol=${session.protocol} cipher=${session.cipherSuite}")
        statusSink?.invoke("AA: TLS ${session.protocol} up")

        val peer = runCatching { session.peerCertificates }.getOrNull()
        if (peer.isNullOrEmpty()) {
            logger.log("  peer: no certificate presented")
        } else {
            peer.forEachIndexed { i, cert ->
                (cert as? X509Certificate)?.let {
                    logger.log("  peer[$i] subject='${it.subjectX500Principal.name}' issuer='${it.issuerX500Principal.name}' sha256=${AaIdentity.fingerprint(it)}")
                }
            }
        }

        runCatching { session.localCertificates }.getOrNull()?.forEachIndexed { i, cert ->
            (cert as? X509Certificate)?.let {
                logger.log("  local[$i] subject='${it.subjectX500Principal.name}' sha256=${AaIdentity.fingerprint(it)}")
            }
        }
        Log.i(TAG, "TLS established ${session.protocol} ${session.cipherSuite}")
    }

    /** Reads and dumps; sends nothing. Everything above TLS is still UNKNOWN. */
    private fun observe(ssl: SSLSocket) {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        var loggedPayload = 0
        val input = ssl.inputStream

        logger.log("observing (sends nothing)")
        while (running.get()) {
            val n = try {
                input.read(buffer)
            } catch (e: IOException) {
                logger.log("read ended: ${describe(e)}")
                break
            }
            if (n < 0) {
                logger.log("peer closed after $total bytes")
                break
            }
            total += n
            if (loggedPayload < config.maxLoggedPayloadBytes) {
                logger.logBytes("RX", buffer, n)
                loggedPayload += n
                if (loggedPayload >= config.maxLoggedPayloadBytes) {
                    logger.log("--- payload logging cap reached (${config.maxLoggedPayloadBytes}B), counting only")
                }
            }
            if (total % (256 * 1024) < n) logger.log("RX total=$total bytes")
        }
        logger.log("observation ended, total RX=$total bytes")
    }

    private fun usableProtocols(ssl: SSLSocket): Array<String> {
        val wanted = listOf("TLSv1.3", "TLSv1.2")
        val usable = ssl.supportedProtocols.filter { it in wanted }
        return (usable.ifEmpty { ssl.supportedProtocols.toList() }).toTypedArray()
    }

    private fun describe(e: Exception): String {
        val cause = e.cause?.let { " <- ${it.javaClass.simpleName}: ${it.message}" } ?: ""
        return "${e.javaClass.simpleName}: ${e.message}$cause"
    }
}
