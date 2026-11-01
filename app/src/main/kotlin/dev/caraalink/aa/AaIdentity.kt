package dev.caraalink.aa

import android.content.Context
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The Android Auto head-unit ("receiver") identity: the cert chain we present
 * to the phone's gearhead during the AA TLS handshake, and the private key
 * that signs for it.
 *
 * See `app/src/main/assets/aa/PROVENANCE.md` for exactly what this credential
 * is and where it came from. Short version: it is the JVC Kenwood leaf issued
 * by Google Automotive Link that openauto/aasdk ship publicly — it is the
 * standard identity for a self-hosted head unit, not something pulled out of
 * CarbitLink's binary.
 *
 * Files are looked up in this order, so the compiled-in copy can be overridden
 * without rebuilding (and removed from a public build entirely):
 *
 *   1. `<externalFilesDir>/aa/headunit.crt` + `headunit.key`
 *   2. `assets/aa/headunit.crt` + `headunit.key`
 *
 * `headunit.crt` may hold just the leaf, or the leaf followed by its issuer —
 * everything in the file is sent as the chain.
 */
object AaIdentity {

    private const val TAG = "AaIdentity"
    private const val ASSET_DIR = "aa"
    private const val CERT_NAME = "headunit.crt"
    private const val KEY_NAME = "headunit.key"
    private val PASSWORD = "caraalink".toCharArray()

    class Loaded(
        val chain: List<X509Certificate>,
        val key: PrivateKey,
        val source: String,
    ) {
        val leaf: X509Certificate get() = chain.first()

        /** Server- and client-capable context; the caller picks the role per socket. */
        val sslContext: SSLContext by lazy { buildSslContext(chain) }

        /**
         * Context that presents ONLY the leaf certificate as our client cert.
         *
         * Matches what the working reference app does: it initialises SSLEngine
         * with `clientCert length=1159` (the leaf alone) and its second
         * handshake flight is 1191 bytes. Ours was 2103 because we also shipped
         * the Google Automotive Link CA in the chain. The CA is still used as a
         * trust anchor, just not sent on the wire.
         */
        val sslContextLeafOnly: SSLContext by lazy { buildSslContext(listOf(leaf)) }

        fun describe(): String = buildString {
            append("identity from $source: ")
            append("leaf='${leaf.subjectX500Principal.name}' ")
            append("issuer='${leaf.issuerX500Principal.name}' ")
            append("serial=0x${leaf.serialNumber.toString(16)} ")
            append("valid=${leaf.notBefore.toInstant()}..${leaf.notAfter.toInstant()} ")
            append("sha256=${fingerprint(leaf)} ")
            append("chain=${chain.size} ")
            append("key=${key.algorithm}/${key.encoded.size * 8}b")
        }

        private fun buildSslContext(presentChain: List<X509Certificate>): SSLContext {
            val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
            ks.setKeyEntry("aa-headunit", key, PASSWORD, presentChain.toTypedArray())

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, PASSWORD)

            // Diagnostic stage: we are not yet verifying who the phone claims to
            // be (the AA gearhead presents no useful client cert at this point),
            // so accept its side and just record what it sent in the receiver.
            // Tighten to the Google Automotive Link CA once the handshake shape
            // is confirmed on real hardware.
            val trust = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            return SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, arrayOf(trust), SecureRandom())
            }
        }
    }

    @Volatile
    private var cached: Loaded? = null

    /** Loads (and caches) the identity. Throws if neither location has a usable pair. */
    @Synchronized
    fun load(context: Context): Loaded =
        cached ?: build(context).also { cached = it }

    fun isAvailable(context: Context): Boolean = runCatching { load(context) }.isSuccess

    private fun build(context: Context): Loaded {
        val certs = readWithSource(context, CERT_NAME)
            ?: error("no $CERT_NAME in externalFilesDir/$ASSET_DIR or assets/$ASSET_DIR")
        val keyBytes = readWithSource(context, KEY_NAME)
            ?: error("no $KEY_NAME in externalFilesDir/$ASSET_DIR or assets/$ASSET_DIR")

        val chain = parseChain(certs.first)
        val key = parseKey(keyBytes.first)
        val loaded = Loaded(chain, key, certs.second)
        Log.i(TAG, loaded.describe())
        return loaded
    }

    private fun readWithSource(context: Context, name: String): Pair<ByteArray, String>? {
        val external = File(File(context.getExternalFilesDir(null), ASSET_DIR), name)
        if (external.isFile && external.length() > 0) {
            return external.readBytes() to external.absolutePath
        }
        val fromAssets = runCatching {
            context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
        }.getOrNull()
        return fromAssets?.let { it to "assets/$ASSET_DIR/$name" }
    }

    private fun parseChain(pem: ByteArray): List<X509Certificate> {
        val factory = CertificateFactory.getInstance("X.509")
        val parsed = factory.generateCertificates(pem.inputStream()).map { it as X509Certificate }
        require(parsed.isNotEmpty()) { "certificate file contained no certificates" }
        return parsed
    }

    /** `headunit.key` is unencrypted PKCS#8; anything else is a packaging bug. */
    private fun parseKey(pem: ByteArray): PrivateKey {
        val text = String(pem, Charsets.US_ASCII)
        require("BEGIN RSA PRIVATE KEY" !in text) {
            "$KEY_NAME is PKCS#1; re-encode as PKCS#8 (openssl pkcs8 -topk8 -nocrypt)"
        }
        val der = Base64.getMimeDecoder().decode(stripPem(text))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    private fun stripPem(text: String): String =
        text.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256")
            .digest(cert.encoded)
            .joinToString(":") { String.format(Locale.US, "%02X", it) }
}
