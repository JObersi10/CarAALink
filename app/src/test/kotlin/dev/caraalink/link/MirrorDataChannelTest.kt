package dev.caraalink.link

import android.content.Context
import dev.caraalink.diagnostics.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/** Headless coverage for tcp:10920 (mirror data): see MirrorControlChannelTest for the pattern. */
class MirrorDataChannelTest {

    private lateinit var host: FakeMirrorHost
    private lateinit var channel: MirrorDataChannel
    private lateinit var stream: FakeStream

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(createTempDir())
        host = FakeMirrorHost()
        channel = MirrorDataChannel(DiagnosticLogger(context, "test"), host)
        stream = FakeStream()
    }

    @Test
    fun `opening the stream tells the host, with that exact stream`() {
        channel.onOpen(stream)

        assertEquals(1, host.opened.size)
        assertSame(stream, host.opened[0])
    }

    @Test
    fun `closing the stream tells the host`() {
        channel.onOpen(stream)
        channel.onClose(stream)

        assertSame(stream, host.closed.single())
    }

    @Test
    fun `a DATA_NEXT pull asks the host for a frame, no reply written yet`() {
        channel.onOpen(stream)
        stream.written.clear() // nothing to clear here, but mirrors the real flow

        channel.onData(stream, req(MirrorDataChannel.REQ_DATA_NEXT, ByteArray(0)))

        assertEquals(1, host.frameRequests.size)
        assertSame(stream, host.frameRequests[0])
    }

    @Test
    fun `an unrecognized request type is refused, not forwarded as a frame pull`() {
        channel.onOpen(stream)

        channel.onData(stream, req(0x1234, ByteArray(0)))

        assertEquals(0, host.frameRequests.size)
        val reply = stream.written.single()
        val type = ByteBuffer.wrap(reply, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(0x7F02, type)
    }

    private fun req(type: Int, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(type.toShort()).putShort(body.size.toShort()).putInt(0).put(body).array()
}
