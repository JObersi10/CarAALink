package dev.caraalink.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protobuf wire-format tests. Field numbers come from the `aap_protobuf`
 * definitions in aasdk (`ServiceDiscoveryRequest.proto`, `Service.proto`).
 */
class AaProtobufTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02X", it) }

    @Test
    fun `string field encodes tag then length then utf8`() {
        // field 5, wire type 2 -> tag byte 0x2A
        val bytes = AaProtobuf.Writer().string(5, "A").build()
        assertEquals("2A 01 41", hex(bytes))
    }

    @Test
    fun `varint field encodes tag then value`() {
        // field 1, wire type 0 -> tag byte 0x08
        val bytes = AaProtobuf.Writer().int(1, 300).build()
        assertEquals("08 AC 02", hex(bytes))
    }

    @Test
    fun `discovery request round trips`() {
        val bytes = AaProtobuf.Writer()
            .string(4, "CarAALink") // label_text
            .string(5, "CarAALink") // device_name
            .build()

        val fields = AaProtobuf.parse(bytes)
        assertEquals(2, fields.size)
        assertEquals(4, fields[0].number)
        assertEquals("CarAALink", AaProtobuf.utf8(fields[0]))
        assertEquals(5, fields[1].number)
        assertEquals("CarAALink", AaProtobuf.utf8(fields[1]))
    }

    @Test
    fun `nested message field parses back out`() {
        val inner = AaProtobuf.Writer().int(1, 7).build()
        val outer = AaProtobuf.Writer().message(1, inner).build()
        val fields = AaProtobuf.parse(outer)
        assertEquals(1, fields.size)
        assertEquals(1, fields[0].number)
        val innerFields = AaProtobuf.parse(fields[0].bytes!!)
        assertEquals(7L, innerFields[0].varint)
    }

    @Test
    fun `large varint round trips`() {
        val bytes = AaProtobuf.Writer().int(13, 2_000_000).build()
        val fields = AaProtobuf.parse(bytes)
        assertEquals(2_000_000L, fields[0].varint)
    }

    @Test
    fun `truncated length-delimited field is rejected`() {
        // field 1, wire type 2, length 5, but only 2 bytes follow
        val bad = byteArrayOf(0x0A, 0x05, 0x41, 0x42)
        val threw = runCatching { AaProtobuf.parse(bad) }.isFailure
        assertTrue("expected malformed protobuf to throw", threw)
    }

    @Test
    fun `unsupported wire type is rejected`() {
        // field 1, wire type 3 (start group) -- not supported
        val bad = byteArrayOf(0x0B)
        assertTrue(runCatching { AaProtobuf.parse(bad) }.isFailure)
    }
}
