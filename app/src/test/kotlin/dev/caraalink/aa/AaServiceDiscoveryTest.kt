package dev.caraalink.aa

import dev.caraalink.aa.AaServiceDiscovery.AaResolution
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Headless coverage for the margin-autodetect geometry (claude/roadmap.md's
 * open per-side-vs-total question): this only tests the aspect-ratio math,
 * not which semantics AA actually gives `width_margin`/`height_margin` on
 * the wire -- that part still needs a live car (see AppSettings.aaAutoCrop).
 */
class AaServiceDiscoveryTest {

    @Test
    fun `this car (1920x720, 2point67-to-1) crops height off a 1920x1080 resolution`() {
        val (widthMargin, heightMargin) = AaServiceDiscovery.autoMargin(AaResolution.R1920X1080, carWidth = 1920, carHeight = 720)

        assertEquals(0, widthMargin)
        // desiredHeight = 1920 / (1920/720) = 720 -> margin = 1080 - 720 = 360
        assertEquals(360, heightMargin)
    }

    @Test
    fun `a resolution wider than the car crops width instead`() {
        // Contrived: a car taller for its width than any real AA resolution (aspect 1.0),
        // so a 16:9 resolution is the one that's "too wide" and needs a width crop.
        val (widthMargin, heightMargin) = AaServiceDiscovery.autoMargin(AaResolution.R1280X720, carWidth = 720, carHeight = 720)

        assertEquals(0, heightMargin)
        // desiredWidth = 720 * (720/720) = 720 -> margin = 1280 - 720 = 560
        assertEquals(560, widthMargin)
    }

    @Test
    fun `an exact aspect match needs no crop`() {
        val (widthMargin, heightMargin) = AaServiceDiscovery.autoMargin(AaResolution.R1280X720, carWidth = 1280, carHeight = 720)

        assertEquals(0, widthMargin)
        assertEquals(0, heightMargin)
    }

    @Test
    fun `phone-plays-audio mode declares only a 16 kHz mono system sink, so AA keeps music on the phone`() {
        val response = AaProtobuf.parse(AaServiceDiscovery.build(AaServiceDiscovery.Config(phoneMediaAudio = true)))
        val audioService = response.filter { it.number == 1 }.map { AaProtobuf.parse(it.bytes!!) }
            .first { svc -> svc.first { it.number == 1 }.varint == AaServiceDiscovery.CHANNEL_MEDIA_SINK_MEDIA_AUDIO.toLong() }
        val sink = AaProtobuf.parse(audioService.first { it.number == 3 }.bytes!!)
        val audioConfig = AaProtobuf.parse(sink.first { it.number == 3 }.bytes!!)

        assertEquals(AaServiceDiscovery.AUDIO_STREAM_SYSTEM.toLong(), sink.first { it.number == 2 }.varint)
        assertEquals(16_000L to 1L, audioConfig.first { it.number == 1 }.varint to audioConfig.first { it.number == 3 }.varint)
    }

    @Test
    fun `unknown car dimensions (no car connected yet) means no crop`() {
        val (widthMargin, heightMargin) = AaServiceDiscovery.autoMargin(AaResolution.R1920X1080, carWidth = 0, carHeight = 0)

        assertEquals(0, widthMargin)
        assertEquals(0, heightMargin)
    }
}
