package dev.caraalink.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.SocketTimeoutException

class CarSessionServerCheckTest {

    @Test
    fun `detects a listening server without connecting to it`() {
        ServerSocket(0).use { server ->
            assertTrue(CarSession.isListening(server.localPort))

            server.soTimeout = 300
            val connected = try { server.accept().close(); true } catch (e: SocketTimeoutException) { false }
            assertFalse("the check must not use up AA's one connection slot", connected)
        }
    }

    @Test
    fun `free port is not listening`() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(CarSession.isListening(port))
    }
}
