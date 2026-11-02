package dev.caraalink.aa

import android.content.Context
import dev.caraalink.settings.AppSettings

/**
 * Builds the head unit's `SERVICE_DISCOVERY_RESPONSE` — the message that tells
 * the phone what this head unit can do. This is where the video resolution is
 * actually chosen.
 *
 * Field numbers are from aasdk's `aap_protobuf` protos (all `proto2`, so
 * optional/required semantics matter):
 *
 * - `service/control/message/ServiceDiscoveryResponse.proto`
 *     channels = 1 (repeated), driver_position = 6, display_name = 14
 * - `service/Service.proto`
 *     id = 1 (required), media_sink_service = 3, input_source_service = 4
 * - `service/media/sink/MediaSinkService.proto`
 *     available_type = 1, video_configs = 4
 * - `service/media/sink/message/VideoConfiguration.proto`
 *     codec_resolution = 1, frame_rate = 2, density = 5, video_codec_type = 10
 * - `service/inputsource/InputSourceService.proto`
 *     touchscreen = 2 { width = 1, height = 2, type = 3 }
 * - enums: `VideoCodecResolutionType`, `VideoFrameRateType`,
 *   `MediaCodecType`, `DriverPosition`
 *
 * ## The resolution problem (important)
 *
 * Android Auto only offers these `VideoCodecResolutionType` values:
 *
 * ```
 * 800x480  1280x720  1920x1080  2560x1440  3840x2160
 * 720x1280 1080x1920 1440x2560 2160x3840        (portrait)
 * ```
 *
 * There is **no 1920x720** — the CarbitLink head unit's 2.67:1 aspect ratio is
 * not something AA will ever render. "Native res" therefore has to mean one of:
 *
 * - [AaResolution.R1280X720]  — exact height match, pillarboxed by the car
 * - [AaResolution.R1920X1080] — exact width match, letterboxed; more pixels
 *
 * Default is 1920x1080 (matches the car's width, so no horizontal scaling).
 * Whatever is chosen, the frames arrive at that size and the CarbitLink side
 * has to place them into 1920x720 rather than assume a 1:1 fit.
 */
object AaServiceDiscovery {

    /**
     * `VideoCodecResolutionType` — only the values AA actually defines
     * (`VideoCodecResolutionType.proto`, confirmed in aasdk). The portrait
     * variants (6-9) exist for head units whose panel is taller than it is
     * wide (e.g. a Tesla-style portrait screen) -- these were previously
     * documented above but never actually implemented, so `configFromSettings`
     * could only ever pick a landscape resolution even for a portrait car.
     */
    enum class AaResolution(val protocolValue: Int, val width: Int, val height: Int) {
        R800X480(1, 800, 480),
        R1280X720(2, 1280, 720),
        R1920X1080(3, 1920, 1080),
        R2560X1440(4, 2560, 1440),
        R3840X2160(5, 3840, 2160),
        R720X1280(6, 720, 1280),
        R1080X1920(7, 1080, 1920),
        R1440X2560(8, 1440, 2560),
        R2160X3840(9, 2160, 3840),
    }

    /** `VideoFrameRateType` */
    const val FPS_60 = 1
    const val FPS_30 = 2

    /** `MediaCodecType` */
    const val CODEC_AUDIO_PCM = 1
    const val CODEC_AUDIO_AAC_LC = 2
    const val CODEC_VIDEO_H264_BP = 3

    /** `AudioStreamType` */
    const val AUDIO_STREAM_MEDIA = 3

    /** `DriverPosition` */
    const val DRIVER_POSITION_LEFT = 0

    /** `SensorType` (sensorsource/message/SensorType.proto) */
    const val SENSOR_LOCATION = 1
    const val SENSOR_NIGHT_MODE = 10
    const val SENSOR_DRIVING_STATUS_DATA = 13

    /** Control-channel channel ids (`ChannelId.hpp`). */
    const val CHANNEL_MEDIA_SINK_VIDEO = 3
    const val CHANNEL_MEDIA_SINK_MEDIA_AUDIO = 4
    const val CHANNEL_SENSOR = 1
    const val CHANNEL_MEDIA_SOURCE_MICROPHONE = 9
    const val CHANNEL_MEDIA_PLAYBACK_STATUS = 13
    const val CHANNEL_PHONE_STATUS = 14
    const val CHANNEL_MEDIA_BROWSER = 15
    const val CHANNEL_VENDOR_EXTENSION = 16
    const val CHANNEL_GENERIC_NOTIFICATION = 17
    const val CHANNEL_WIFI_PROJECTION = 18
    const val CHANNEL_INPUT_SOURCE = 8

    data class Config(
        val resolution: AaResolution = AaResolution.R1920X1080,
        val frameRate: Int = FPS_30,
        /** Physical density; 160 is the AA convention for 1x layouts. */
        val density: Int = 160,
        /** Touch area reported to the phone, in the head unit's own pixels. */
        val touchWidth: Int = 1920,
        val touchHeight: Int = 720,
        /**
         * Pixels of the rendered video that are cropped/masked per axis.
         * AA uses these to fit a 16:9 render onto a differently-shaped panel.
         * Semantics taken from openauto's usage only (it exposes them as user
         * settings); exact AA behaviour is NOT confirmed here, so both default
         * to 0 and the car letterboxes instead.
         */
        val widthMargin: Int = 0,
        val heightMargin: Int = 0,
        val displayName: String = "CarAALink",
        val driverPosition: Int = DRIVER_POSITION_LEFT,
    )

    /**
     * Builds [Config] from Settings > Video. AA only ever offers the 9 fixed
     * [AaResolution] values (see the class doc) -- there is no way to
     * request an arbitrary custom size from the phone, so "Custom
     * width/height" picks whichever is closest by pixel count *among
     * resolutions matching the car's orientation setting*, rather than being
     * sent verbatim. A portrait car asking for a landscape resolution (or
     * vice versa) is what made the old "native res" option look wrong/huge
     * on a portrait head unit -- AA has separate portrait codec-resolution
     * values (6-9) and picking from the landscape set regardless of the
     * car's actual shape sent the phone a sideways frame every time.
     * Frame rate only has two real values (30/60); anything else in the
     * setting falls back to 30.
     */
    fun configFromSettings(context: Context): Config {
        val portrait = when (AppSettings.carOrientation(context)) {
            AppSettings.CarOrientation.PORTRAIT -> true
            AppSettings.CarOrientation.LANDSCAPE -> false
            // AUTO: go by what the car itself reported; unknown (no car
            // connected yet, e.g. the on-phone preview) falls back to
            // landscape, the overwhelmingly common case.
            AppSettings.CarOrientation.AUTO -> dev.caraalink.link.CarScreenInfo.isPortrait ?: false
        }
        val candidates = AaResolution.values().filter { (it.height > it.width) == portrait }
        val defaultRes = if (portrait) AaResolution.R1080X1920 else AaResolution.R1920X1080
        val resolution = if (AppSettings.resolutionMode(context) == AppSettings.ResolutionMode.CUSTOM) {
            val (w, h) = AppSettings.customResolution(context) ?: (defaultRes.width to defaultRes.height)
            candidates.minByOrNull { kotlin.math.abs(it.width * it.height - w * h) } ?: defaultRes
        } else {
            defaultRes
        }
        val frameRate = if (AppSettings.frameRate(context) >= 60) FPS_60 else FPS_30
        val density = AppSettings.videoDpi(context, resolution.width, resolution.height)
        // Advertise the touchscreen at the same size as the video we're
        // actually sending -- AaCarPresentation/AaPreviewActivity map touch
        // events into resolution.width/height, so a mismatched advertised
        // touch size (the old hardcoded 1920x720 default) would have the
        // phone interpreting touch in the wrong coordinate space.
        return Config(
            resolution = resolution, frameRate = frameRate, density = density,
            touchWidth = resolution.width, touchHeight = resolution.height,
        )
    }

    /**
     * Serialized `ServiceDiscoveryResponse`. Deliberately minimal: a video
     * sink and an input source, which is what the CarAALink path needs. Audio
     * and sensor channels are omitted rather than guessed at — add them from
     * the protos when there is evidence they are needed.
     */
    fun build(config: Config = Config()): ByteArray {
        val videoConfig = AaProtobuf.Writer()
            .int(1, config.resolution.protocolValue) // codec_resolution
            .int(2, config.frameRate)                // frame_rate
            .int(3, config.widthMargin)              // width_margin
            .int(4, config.heightMargin)             // height_margin
            .int(5, config.density)                  // density
            .int(10, CODEC_VIDEO_H264_BP)            // video_codec_type
            .build()

        // openauto's VideoMediaSinkService::fillFeatures() sets available_type
        // to MEDIA_CODEC_VIDEO_H264_BP and available_while_in_call=true on a
        // *video* sink. Our earlier MEDIA_CODEC_AUDIO_PCM there was wrong and
        // is the likely reason the phone dropped us right after discovery.
        val mediaSink = AaProtobuf.Writer()
            .int(1, CODEC_VIDEO_H264_BP)             // available_type
            .bool(5, true)                           // available_while_in_call
            .message(4, videoConfig)                 // video_configs (repeated)
            .build()

        val touchscreen = AaProtobuf.Writer()
            .int(1, config.touchWidth)
            .int(2, config.touchHeight)
            .build()

        val inputSource = AaProtobuf.Writer()
            .message(2, touchscreen)                 // touchscreen (repeated)
            .build()

        // AudioConfiguration: sampling_rate=1, number_of_bits=2,
        // number_of_channels=3 (all required). AudioStreamType: MEDIA=3.
        // openauto registers media/guidance/system/telephony audio sinks; the
        // phone's endpoint start failed with only video+input, so start with
        // the media sink (48 kHz / 16-bit / stereo, AAC-LC).
        val audioConfig = AaProtobuf.Writer()
            .int(1, 48_000)   // sampling_rate
            .int(2, 16)       // number_of_bits
            .int(3, 2)        // number_of_channels
            .build()

        val audioSink = AaProtobuf.Writer()
            .int(1, CODEC_AUDIO_AAC_LC)    // available_type
            .int(2, AUDIO_STREAM_MEDIA)    // audio_type
            .message(3, audioConfig)       // audio_configs (repeated)
            .build()

        val audioService = AaProtobuf.Writer()
            .int(1, CHANNEL_MEDIA_SINK_MEDIA_AUDIO)
            .message(3, audioSink)         // media_sink_service
            .build()

        val videoService = AaProtobuf.Writer()
            .int(1, CHANNEL_MEDIA_SINK_VIDEO)        // id (required)
            .message(3, mediaSink)                   // media_sink_service
            .build()

        val inputService = AaProtobuf.Writer()
            .int(1, CHANNEL_INPUT_SOURCE)            // id (required)
            .message(4, inputSource)                 // input_source_service
            .build()

        // ConnectionConfiguration.ping_configuration -- values copied from
        // openauto's AndroidAutoEntity::onServiceDiscoveryRequest(), which is a
        // known-good head unit: tracked=5, timeout=3000ms, interval=1000ms,
        // high-latency threshold=200ms. The phone appears to need this block;
        // without it our earlier 46-byte response was rejected with an EOF.
        val ping = AaProtobuf.Writer()
            .int(1, 3000)   // timeout_ms
            .int(2, 1000)   // interval_ms
            .int(3, 200)    // high_latency_threshold_ms
            .int(4, 5)      // tracked_ping_count
            .build()
        val connectionConfig = AaProtobuf.Writer()
            .message(1, ping) // ping_configuration
            .build()

        val headUnitInfo = AaProtobuf.Writer()
            .string(1, "CarAALink")   // make
            .string(2, "Universal")   // model
            .string(3, "2026")        // year
            .string(4, "caraalink")   // vehicle_id
            .string(5, "CarAALink")   // head_unit_make
            .string(6, "CarAALink")   // head_unit_model
            .string(7, "1")           // head_unit_software_build
            .string(8, "0.1.0")       // head_unit_software_version
            .build()

        // SensorSourceService.sensors (field 1, repeated Sensor{sensor_type=1
        // required}). openauto's SensorService::fillFeatures() advertises
        // exactly these three (driving status, location, night mode); matched
        // byte-for-byte rather than left empty, since it's cheap and this is a
        // known-good reference.
        val sensorService = AaProtobuf.Writer()
            .int(1, CHANNEL_SENSOR)
            .message(
                2,
                AaProtobuf.Writer()
                    .message(1, AaProtobuf.Writer().int(1, SENSOR_DRIVING_STATUS_DATA).build())
                    .message(1, AaProtobuf.Writer().int(1, SENSOR_LOCATION).build())
                    .message(1, AaProtobuf.Writer().int(1, SENSOR_NIGHT_MODE).build())
                    .build(),
            )
            .build()

        // MediaSourceService (microphone, channel 9): available_type=1,
        // audio_config=2 {sampling_rate, number_of_bits, number_of_channels},
        // available_while_in_call=3. openauto's MediaSourceService::
        // fillFeatures() always fills audio_config from its real input device
        // -- an EMPTY body here (no codec, no config) is almost certainly why
        // CAR.SERVICE logged "Critical error 2 detail: 24 msg: No audio/mic"
        // immediately before the phone disconnected. 16 kHz/16-bit/mono is the
        // standard AA mic format (aasdk/openauto default input config).
        val micAudioConfig = AaProtobuf.Writer()
            .int(1, 16_000)   // sampling_rate
            .int(2, 16)       // number_of_bits
            .int(3, 1)        // number_of_channels
            .build()
        val micService = AaProtobuf.Writer()
            .int(1, CODEC_AUDIO_PCM)      // available_type
            .message(2, micAudioConfig)   // audio_config
            .build()
        val microphoneService = AaProtobuf.Writer()
            .int(1, CHANNEL_MEDIA_SOURCE_MICROPHONE)
            .message(5, micService)       // media_source_service
            .build()

        // Remaining services openauto registers. These have no required fields
        // (checked against their .proto), so an empty body is valid. Skipped on
        // purpose: bluetooth (required car_address), navigation_status (required
        // interval + cluster type) -- nothing to guess about those yet.
        val emptyServiceExtra = listOf(
            CHANNEL_MEDIA_PLAYBACK_STATUS to 9,   // media_playback_service
            CHANNEL_PHONE_STATUS to 10,           // phone_status_service
            CHANNEL_MEDIA_BROWSER to 11,          // media_browser_service
            CHANNEL_GENERIC_NOTIFICATION to 13,   // generic_notification_service
            CHANNEL_WIFI_PROJECTION to 14,        // wifi_projection_service
        ).map { (channel, field) ->
            AaProtobuf.Writer()
                .int(1, channel)
                .message(field, ByteArray(0))
                .build()
        }

        val vendorExtension = AaProtobuf.Writer()
            .string(1, config.displayName)   // VendorExtensionService.name (required)
            .build()

        val vendorService = AaProtobuf.Writer()
            .int(1, CHANNEL_VENDOR_EXTENSION)
            .message(12, vendorExtension)
            .build()

        val builder = AaProtobuf.Writer()
            .message(1, videoService)                // channels (repeated)
            .message(1, audioService)
            .message(1, inputService)
            .message(1, sensorService)
            .message(1, microphoneService)
        emptyServiceExtra.forEach { builder.message(1, it) }
        return builder.message(1, vendorService)
            .int(6, config.driverPosition)           // driver_position
            .string(14, config.displayName)          // display_name
            .bool(15, false)                         // probe_for_support
            .message(16, connectionConfig)           // connection_configuration
            .message(17, headUnitInfo)               // headunit_info
            .build()
    }

    /** Human-readable summary for the log. */
    fun describe(config: Config = Config()): String =
        "video=${config.resolution.width}x${config.resolution.height}" +
            "@${if (config.frameRate == FPS_60) 60 else 30}fps h264bp, " +
            "touch=${config.touchWidth}x${config.touchHeight}, name='${config.displayName}'"
}
