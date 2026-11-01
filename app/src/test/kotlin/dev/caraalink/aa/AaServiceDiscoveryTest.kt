package dev.caraalink.aa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural tests for the head unit's service discovery response. Field
 * numbers are pinned to aasdk's protos so the phone has something it can parse.
 *
 * Channels are located by their `Service.id` rather than by index, so adding a
 * service doesn't silently invalidate the assertions.
 */
class AaServiceDiscoveryTest {

    private val config = AaServiceDiscovery.Config(
        resolution = AaServiceDiscovery.AaResolution.R1920X1080,
        touchWidth = 1920,
        touchHeight = 720,
    )

    /** Map of Service.id -> parsed Service fields. */
    private fun channels(): Map<Long, List<AaProtobuf.Field>> =
        AaProtobuf.parse(AaServiceDiscovery.build(config))
            .filter { it.number == 1 }
            .associate { serviceFields ->
                val parsed = AaProtobuf.parse(serviceFields.bytes!!)
                parsed.first { it.number == 1 }.varint to parsed
            }

    @Test
    fun `video sink channel is present and typed`() {
        val video = channels()[AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO.toLong()]
        assertNotNull("media sink video channel missing", video)
        assertTrue("video channel must carry media_sink_service (3)", video!!.any { it.number == 3 })
    }

    @Test
    fun `input source channel is present and typed`() {
        val input = channels()[AaServiceDiscovery.CHANNEL_INPUT_SOURCE.toLong()]
        assertNotNull("input source channel missing", input)
        assertTrue("input channel must carry input_source_service (4)", input!!.any { it.number == 4 })
    }

    @Test
    fun `media audio sink channel is present`() {
        val audio = channels()[AaServiceDiscovery.CHANNEL_MEDIA_SINK_MEDIA_AUDIO.toLong()]
        assertNotNull("media audio channel missing", audio)
        assertTrue(audio!!.any { it.number == 3 })
    }

    @Test
    fun `video configuration carries resolution frame rate and codec`() {
        val video = channels()[AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO.toLong()]!!
        val mediaSink = video.first { it.number == 3 }.bytes!!
        val videoConfigs = AaProtobuf.parse(mediaSink).filter { it.number == 4 }
        assertEquals(1, videoConfigs.size)

        val vc = AaProtobuf.parse(videoConfigs[0].bytes!!)
        assertEquals(
            AaServiceDiscovery.AaResolution.R1920X1080.protocolValue.toLong(),
            vc.first { it.number == 1 }.varint,
        )
        assertEquals(AaServiceDiscovery.FPS_30.toLong(), vc.first { it.number == 2 }.varint)
        assertEquals(AaServiceDiscovery.CODEC_VIDEO_H264_BP.toLong(), vc.first { it.number == 10 }.varint)
    }

    @Test
    fun `video sink advertises h264 not audio`() {
        val video = channels()[AaServiceDiscovery.CHANNEL_MEDIA_SINK_VIDEO.toLong()]!!
        val mediaSink = AaProtobuf.parse(video.first { it.number == 3 }.bytes!!)
        assertEquals(
            "available_type must be MEDIA_CODEC_VIDEO_H264_BP on a video sink",
            AaServiceDiscovery.CODEC_VIDEO_H264_BP.toLong(),
            mediaSink.first { it.number == 1 }.varint,
        )
    }

    @Test
    fun `touchscreen reports the car pixel area`() {
        val input = channels()[AaServiceDiscovery.CHANNEL_INPUT_SOURCE.toLong()]!!
        val body = AaProtobuf.parse(input.first { it.number == 4 }.bytes!!)
        val touch = AaProtobuf.parse(body.first { it.number == 2 }.bytes!!)
        assertEquals(1920L, touch.first { it.number == 1 }.varint)
        assertEquals(720L, touch.first { it.number == 2 }.varint)
    }

    @Test
    fun `driver position and display name are present`() {
        val fields = AaProtobuf.parse(AaServiceDiscovery.build(config))
        assertEquals(AaServiceDiscovery.DRIVER_POSITION_LEFT.toLong(), fields.first { it.number == 6 }.varint)
        assertEquals("CarAALink", AaProtobuf.utf8(fields.first { it.number == 14 }))
    }

    @Test
    fun `connection configuration carries the ping cadence`() {
        val fields = AaProtobuf.parse(AaServiceDiscovery.build(config))
        val connection = AaProtobuf.parse(fields.first { it.number == 16 }.bytes!!)
        val ping = AaProtobuf.parse(connection.first { it.number == 1 }.bytes!!)
        assertEquals(3000L, ping.first { it.number == 1 }.varint)
        assertEquals(1000L, ping.first { it.number == 2 }.varint)
        assertEquals(200L, ping.first { it.number == 3 }.varint)
        assertEquals(5L, ping.first { it.number == 4 }.varint)
    }

    @Test
    fun `head unit info is populated`() {
        val fields = AaProtobuf.parse(AaServiceDiscovery.build(config))
        val info = AaProtobuf.parse(fields.first { it.number == 17 }.bytes!!)
        assertEquals("CarAALink", AaProtobuf.utf8(info.first { it.number == 1 }))
    }

    @Test
    fun `no AA resolution offers 1920x720`() {
        // Documents the constraint behind keeping 16:9: the car is 2.67:1 and
        // AA has no matching mode, so the display side always fits/letterboxes.
        val carAspect = 1920.0 / 720.0
        val offered = AaServiceDiscovery.AaResolution.values().map { it.width.toDouble() / it.height }
        assertTrue("no AA mode matches the car's aspect", offered.none { kotlin.math.abs(it - carAspect) < 0.01 })
    }
}
