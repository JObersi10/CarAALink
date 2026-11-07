package dev.caraalink.aa

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZonedDateTime

class NightModeTest {

    private fun cairo(time: String) =
        NightMode.isNight(30.04, 31.24, ZonedDateTime.parse("2026-11-07T$time+02:00[Africa/Cairo]").toInstant().toEpochMilli())

    // sunrise-sunset.org, Cairo 7 Nov 2026: sunrise 06:13, sunset 17:03.
    @Test
    fun `flips within minutes of Cairo's real sunrise and sunset`() {
        val expected = mapOf("06:06" to true, "06:20" to false, "12:00" to false, "16:56" to false, "17:10" to true, "23:00" to true)
        assertEquals(expected, expected.keys.associateWith { cairo(it) })
    }
}
