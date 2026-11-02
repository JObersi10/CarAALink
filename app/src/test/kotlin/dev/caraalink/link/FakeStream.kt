package dev.caraalink.link

/**
 * Headless stand-in for [AdbTransport.Stream] -- no USB accessory, no
 * ADB framing, just what [MirrorStream] promises. Captures every
 * `write()` so a test can assert on the exact reply bytes a channel sent.
 */
class FakeStream(override val localId: Int = 1) : MirrorStream {
    val written = mutableListOf<ByteArray>()
    override var closed = false
        private set
    private var backlog = 0

    override fun write(data: ByteArray) {
        written.add(data)
        backlog += data.size
    }

    override fun backlogBytes(): Int = backlog

    override fun clearBacklog() {
        backlog = 0
    }

    fun close() {
        closed = true
    }
}
