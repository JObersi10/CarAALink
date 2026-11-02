package dev.caraalink.aa

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the AA wire codec. These need no device: they pin the
 * byte layout against aasdk's definitions so it cannot silently regress.
 *
 * FrameHeader.cpp / FrameSize.cpp / MessageId.cpp are the source of truth for
 * every assertion here.
 */
class AaFramingTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    @Test
    fun `version request matches aasdk byte for byte`() {
        // Confirmed on hardware: gearhead answered this with VERSION_RESPONSE.
        assertEquals("00 03 00 06 00 01 00 01 00 06", hex(AaFraming.versionRequest()))
    }

    @Test
    fun `version request honours an explicit version`() {
        val bytes = AaFraming.versionRequest(major = 4, minor = 1)
        assertEquals("00 03 00 06 00 01 00 04 00 01", hex(bytes))
    }

    @Test
    fun `plain bulk frame header layout`() {
        val payload = byteArrayOf(1, 2, 3)
        val frame = AaFraming.frame(
            AaFraming.CHANNEL_CONTROL,
            AaFraming.FRAME_BULK,
            AaFraming.ENCRYPTION_PLAIN,
            AaFraming.MESSAGE_SPECIFIC,
            payload,
        )
        // channel 0, flags = PLAIN|SPECIFIC|BULK = 0x03, size = 3, then payload.
        assertEquals("00 03 00 03 01 02 03", hex(frame))
    }

    @Test
    fun `encrypted control flag byte is 0x0B`() {
        // ENCRYPTED(0x08) | SPECIFIC(0x00) | BULK(0x03)
        val frame = AaFraming.frame(
            AaFraming.CHANNEL_CONTROL,
            AaFraming.FRAME_BULK,
            AaFraming.ENCRYPTION_ENCRYPTED,
            AaFraming.MESSAGE_SPECIFIC,
            byteArrayOf(9),
        )
        assertEquals(0x00, frame[0].toInt())
        assertEquals(0x0B, frame[1].toInt())
    }

    @Test
    fun `first frame uses the extended size field`() {
        val payload = ByteArray(16) { it.toByte() }
        val frame = AaFraming.frame(
            AaFraming.CHANNEL_CONTROL,
            AaFraming.FRAME_FIRST,
            AaFraming.ENCRYPTION_PLAIN,
            AaFraming.MESSAGE_SPECIFIC,
            payload,
            totalSize = 100,
        )
        // 2 header + 6 extended size + 16 payload
        assertEquals(24, frame.size)
        assertEquals(0x01, frame[1].toInt())
        assertEquals(16, AaFraming.u16(frame, 2))
        assertEquals(100, AaFraming.u32(frame, 4))
    }

    @Test
    fun `control message prefixes the big endian message id`() {
        val frame = AaFraming.controlMessage(AaFraming.MSG_PING_RESPONSE, byteArrayOf(0xAA.toByte()))
        // channel, flags(BULK|PLAIN|SPECIFIC), size 3, msgid 0x000C, body
        assertEquals("00 03 00 03 00 0C AA", hex(frame))
    }

    @Test
    fun `uint16 and uint32 are big endian`() {
        val out = ByteArray(6)
        AaFraming.putU16(out, 0, 0x1234)
        AaFraming.putU32(out, 2, 0x01020304)
        assertEquals("12 34 01 02 03 04", hex(out))
        assertEquals(0x1234, AaFraming.u16(out, 0))
        assertEquals(0x01020304, AaFraming.u32(out, 2))
    }

    @Test
    fun `frame type names round trip`() {
        assertEquals("BULK", AaFraming.frameTypeName(AaFraming.FRAME_BULK))
        assertEquals("FIRST", AaFraming.frameTypeName(AaFraming.FRAME_FIRST))
        assertEquals("LAST", AaFraming.frameTypeName(AaFraming.FRAME_LAST))
        assertEquals("MIDDLE", AaFraming.frameTypeName(AaFraming.FRAME_MIDDLE))
    }

    @Test
    fun `service discovery response from gearhead parses`() {
        // Real bytes captured from the phone: 00 02 | 00 01 | 00 07 | 00 00
        val payload = byteArrayOf(0x00, 0x02, 0x00, 0x01, 0x00, 0x07, 0x00, 0x00)
        assertEquals(AaFraming.MSG_VERSION_RESPONSE, AaFraming.controlMessageId(payload))
        assertEquals(1, AaFraming.u16(payload, 2))
        assertEquals(7, AaFraming.u16(payload, 4))
        assertEquals(0, AaFraming.u16(payload, 6))
    }
}
