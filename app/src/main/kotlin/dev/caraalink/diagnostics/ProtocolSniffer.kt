package dev.caraalink.diagnostics

/**
 * Shared hex-dump helper for the two protocol logs (CarbitLink mirror
 * control channel, AA control messages) when Settings > Debug >
 * "Verbose protocol logging" is on. Deliberately just a formatter -- the
 * gating and the actual field-level logging stay in each channel, which is
 * what already knows the confirmed message layout for its own protocol.
 */
object ProtocolSniffer {
    fun hexPreview(data: ByteArray, max: Int = 32): String {
        if (data.isEmpty()) return ""
        val shown = data.copyOfRange(0, minOf(max, data.size))
        val hex = shown.joinToString(" ") { "%02X".format(it) }
        return if (data.size > max) "[$hex ...]" else "[$hex]"
    }
}
