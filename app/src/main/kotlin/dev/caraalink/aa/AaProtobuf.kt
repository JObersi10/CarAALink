package dev.caraalink.aa

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf reader/writer — just enough of the wire format for AA's
 * control messages. No codegen, no runtime dependency.
 *
 * Wire types used: 0 = varint, 2 = length-delimited (string/bytes/message).
 * Field numbers come from the `aap_protobuf` `.proto` files in aasdk, which
 * are quoted at each call site.
 */
object AaProtobuf {

    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH = 2
    const val WIRE_FIXED32 = 5

    class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer {
            tag(field, WIRE_VARINT)
            rawVarint(value)
            return this
        }

        fun int(field: Int, value: Int): Writer = varint(field, value.toLong())

        fun bool(field: Int, value: Boolean): Writer = varint(field, if (value) 1L else 0L)

        fun string(field: Int, value: String): Writer =
            bytes(field, value.toByteArray(Charsets.UTF_8))

        fun bytes(field: Int, value: ByteArray): Writer {
            tag(field, WIRE_LENGTH)
            rawVarint(value.size.toLong())
            out.write(value)
            return this
        }

        fun message(field: Int, body: ByteArray): Writer = bytes(field, body)

        fun build(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

        private fun rawVarint(value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v != 0L) out.write(b or 0x80) else { out.write(b); return }
            }
        }
    }

    data class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?)

    /** Walks [data] and returns every field in order. Throws on malformed input. */
    fun parse(data: ByteArray): List<Field> {
        val fields = ArrayList<Field>()
        var i = 0
        while (i < data.size) {
            val (tag, nextTag) = readVarint(data, i)
            i = nextTag
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            when (wireType) {
                WIRE_VARINT -> {
                    val (v, next) = readVarint(data, i)
                    i = next
                    fields += Field(number, wireType, v, null)
                }
                WIRE_LENGTH -> {
                    val (len, next) = readVarint(data, i)
                    i = next
                    val n = len.toInt()
                    require(n >= 0 && i + n <= data.size) { "length $n overruns buffer at $i/${data.size}" }
                    fields += Field(number, wireType, 0, data.copyOfRange(i, i + n))
                    i += n
                }
                WIRE_FIXED64 -> { fields += Field(number, wireType, 0, data.copyOfRange(i, i + 8)); i += 8 }
                WIRE_FIXED32 -> { fields += Field(number, wireType, 0, data.copyOfRange(i, i + 4)); i += 4 }
                else -> throw IllegalArgumentException("unsupported wire type $wireType (field $number)")
            }
        }
        return fields
    }

    fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result to i
            shift += 7
            require(shift <= 63) { "varint too long" }
        }
        throw IllegalArgumentException("truncated varint at $offset")
    }

    fun utf8(f: Field): String = String(f.bytes ?: ByteArray(0), Charsets.UTF_8)
}
