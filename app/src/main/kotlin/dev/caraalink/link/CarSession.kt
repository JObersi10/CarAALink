package dev.caraalink.link

import android.content.Context
import android.graphics.Color
import android.hardware.usb.UsbAccessory
import android.media.projection.MediaProjection
import android.util.Log
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.R
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.usb.UsbAccessoryConnection
import dev.caraalink.video.CarDisplay
import dev.caraalink.video.CarHomePresentation
import dev.caraalink.video.CarVideoEncoder
import dev.caraalink.video.WeatherProvider
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * One connection to the head unit: ADB transport, PXC control, mirror
 * control/data, H.264 encoder and what's on the car screen.
 */
class CarSession(
    private val context: Context,
    private val accessory: UsbAccessory,
    private val phone: PhoneActions,
    private val onEnded: (String) -> Unit,
) : MirrorHost {

    interface PhoneActions {
        fun requestScreenCapture()
        fun back()
    }

    private val logger = DiagnosticLogger(context, "car-session")
    private var transport: AdbTransport? = null
    private var config: CaptureConfig? = null
    private var encoder: CarVideoEncoder? = null
    private var pxc: PxcChannel? = null
    private val features = CarFeatures(context)
    private val display = CarDisplay(context, listOf(
        CarHomePresentation.Tile("Android Auto", R.drawable.ic_tile_android_auto, Color.parseColor("#1E8E3E")) { openAndroidAuto() },
        CarHomePresentation.Tile("Phone screen", R.drawable.ic_tile_phone_screen, Color.parseColor("#1A73E8")) { openMirror() },
        CarHomePresentation.Tile("Settings", R.drawable.ic_tile_settings, Color.parseColor("#5F6368")) { showSettings() },
    ), onBack = { phone.back() }, onExit = ::exitToHome)

    private fun exitToHome(): Unit { display.showHome() }


    private val frameLock = Any()
    @Volatile private var dataStream: AdbTransport.Stream? = null
    private var codecConfig: ByteArray? = null
    private var configSent = false
    private val pullQueue = ArrayDeque<ByteArray>()
    private var framesSent = 0L
    private var framesEncoded = 0L
    private var frameRequests = 0L
    private var pendingRequests = 0
    private var waitingForKey = true
    private var lastKeyFrame: ByteArray? = null

    fun start(): Boolean {
        val conn = UsbAccessoryConnection.open(context, accessory) ?: run {
            logger.log("openAccessory() returned null")
            return false
        }
        logger.log("Opened accessory ${accessory.manufacturer}/${accessory.model}")
        ConnectionStateHolder.update {
            it.copy(phase = ConnectionPhase.ATTACHED, accessoryManufacturer = accessory.manufacturer,
                accessoryModel = accessory.model, lastError = null)
        }
        features.start()
        val pxc = PxcChannel(logger, { info -> onClientInfo(info) }, features::replyBody).also { this.pxc = it }
        val ctrl = MirrorControlChannel(logger, this)
        val data = MirrorDataChannel(logger, this)
        transport = AdbTransport(conn, logger, { service ->
            when (service) {
                "tcp:10922" -> pxc
                "tcp:10921" -> ctrl
                "tcp:10920" -> data
                else -> null
            }
        }) { reason ->
            Log.i(TAG, "transport closed: $reason")
            release()
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = reason) }
            onEnded(reason)
        }.also { it.start() }
        startWeatherUpdates()
        return true
    }

    @Volatile private var weatherThread: Thread? = null

    private fun startWeatherUpdates() {
        weatherThread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    WeatherProvider.fetch(context)?.let { display.setInfo(it) }
                    Thread.sleep(15 * 60 * 1000L)
                }
            } catch (_: InterruptedException) {
            }
        }, "CarWeather").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        transport?.stop("stopped by user") ?: release()
    }

    fun setProjection(projection: MediaProjection) {
        val show = mirrorWanted
        mirrorWanted = false
        display.setProjection(projection, showMirrorNow = show)
        logger.log("Screen capture granted (showMirror=$show)")
    }

    private fun release() {
        dev.caraalink.aa.AaSessionProbe.active?.stop()
        aaDecoder?.stop()
        aaDecoder = null
        weatherThread?.interrupt()
        weatherThread = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { features.stop() }
        encoder?.release()
        encoder = null
        display.release()
        synchronized(frameLock) { dataStream = null; pullQueue.clear() }
    }

    private fun onClientInfo(info: JSONObject) {
        logger.log("Head unit: ${info.optString("HUName")} screenType=${info.optInt("screenType")} " +
            "mirror=${info.optBoolean("supportScreenMirroring")} touch=${info.optBoolean("supportScreenTouch")}")
    }

    // --- car home screen actions ---

    private var aaDecoder: dev.caraalink.aa.AaVideoDecoder? = null

    /**
     * Starts the real AA receiver (dev.caraalink.aa.AaSessionProbe -- the
     * old AaTlsReceiver above this comment used to be wired here, but it's
     * a dead end that never got past a raw TLS handshake; superseded) and
     * shows its decoded video + live touch on the car's own screen, at
     * whatever resolution Settings > Video actually negotiates. Audio plays
     * on the *phone's* speaker -- CarbitLink's own wire protocol doesn't
     * carry audio to the car at all yet (not implemented anywhere, mirroring
     * included), so that's a separate, later piece of work.
     */
    private fun openAndroidAuto() {
        if (dev.caraalink.aa.AaSessionProbe.active != null) {
            display.setStatus("AA: already running")
            showAaSurface()
            return
        }
        display.setStatus("AA: checking head unit server…")
        logger.log("openAndroidAuto: checking 127.0.0.1:5277 before starting")
        Thread({
            val serverUp = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5277), 400) }
                true
            }.getOrDefault(false)
            if (!serverUp) {
                logger.log("openAndroidAuto: server not listening -- triggering accessibility auto-start")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    display.setStatus("AA: starting head unit server…")
                    dev.caraalink.accessibility.HeadUnitServerLauncher.start(context)
                }
                // Give the accessibility-driven toggle a moment to actually bind the
                // listener before we try to connect to it.
                Thread.sleep(2500)
            }
            logger.log("openAndroidAuto: starting AA session probe")
            dev.caraalink.aa.AaSessionProbe(context).start()
            android.os.Handler(android.os.Looper.getMainLooper()).post { showAaSurface() }
        }, "AA-server-check").start()
    }

    private fun showAaSurface() {
        val config = dev.caraalink.aa.AaServiceDiscovery.configFromSettings(context)
        display.showAa(
            videoWidth = config.resolution.width,
            videoHeight = config.resolution.height,
            onSurface = { surface ->
                if (surface != null) {
                    val dec = dev.caraalink.aa.AaVideoDecoder(config.resolution.width, config.resolution.height)
                    dec.start(surface)
                    aaDecoder = dec
                    dev.caraalink.aa.AaSessionProbe.active?.attachVideoSink { ts, nal -> aaDecoder?.feed(ts, nal) }
                } else {
                    dev.caraalink.aa.AaSessionProbe.active?.videoSink = null
                    aaDecoder?.stop()
                    aaDecoder = null
                }
            },
            onTouch = { x, y, action -> dev.caraalink.aa.AaSessionProbe.active?.sendTouch(x, y, action) },
        )
    }

    @Volatile private var mirrorWanted = false

    private fun openMirror() {
        if (display.showMirror()) return
        mirrorWanted = true
        askForCapture()
    }

    private fun askForCapture() {
        display.setStatus("Tap \"Start now\" on your phone to allow screen mirroring")
        phone.requestScreenCapture()
    }

    private fun showSettings() {
        val c = config
        display.setStatus(
            if (c == null) "No video config yet"
            else "Car ${c.width}x${c.height}, ${if (c.wantFps == 0) "on demand" else "${c.wantFps} fps"}, " +
                "H.264 ${(if (c.bitRate > 0) c.bitRate else DEFAULT_BITRATE) / 1000} kbps, frames sent $framesSent"
        )
    }

    // --- MirrorHost ---

    override fun onCaptureConfig(config: CaptureConfig) {
        this.config = config
        CarScreenInfo.update(config.width, config.height)
        encoder?.release()
        val fps = if (config.wantFps > 0) config.wantFps else 30
        // Settings > Video > bitrate used to be decorative -- nothing ever
        // read it, the encoder always used either the car's own requested
        // bitrate or DEFAULT_BITRATE. Now it's a floor: the car's request
        // (it may know its own decode limits) is still respected if it's
        // already asking for more than the setting.
        val settingsBitrate = dev.caraalink.settings.AppSettings.videoBitrateKbps(context) * 1000
        val carBitrate = if (config.bitRate > 0) config.bitRate else DEFAULT_BITRATE
        val bitRate = maxOf(carBitrate, settingsBitrate)
        val enc = CarVideoEncoder(config.encodeWidth, config.encodeHeight, fps, bitRate, ::onEncodedFrame)
        try {
            enc.start()
        } catch (e: Exception) {
            logger.log("encoder start failed: $e")
            return
        }
        synchronized(frameLock) {
            codecConfig = null
            configSent = false
            pullQueue.clear()
        }
        encoder = enc
        display.attach(enc.inputSurface, config.encodeWidth, config.encodeHeight)
        display.setStatus("Connected")
    }

    override fun onStartVideo(): Boolean {
        encoder?.requestKeyFrame()
        return encoder != null
    }

    override fun onTouch(action: Int, x: Int, y: Int) {
        val c = config ?: return
        val enc = encoder ?: return
        // Car coordinates are in its own screen size; the encoder may be a few pixels smaller.
        val fx = x * enc.width.toFloat() / c.width
        val fy = y * enc.height.toFloat() / c.height
        display.touch(action, fx, fy)
    }

    override fun onKey(key: Int) {
        logger.log("car key $key")
        if (key == MirrorControlChannel.KEY_HOME || key == MirrorControlChannel.KEY_BACK) {
            display.showHome()
            display.setStatus("Connected")
        }
    }

    override fun onDataStreamOpen(stream: AdbTransport.Stream) {
        synchronized(frameLock) {
            dataStream = stream
            configSent = false
            waitingForKey = true
            pendingRequests = 0
            pullQueue.clear()
        }
        logger.log("data stream open; encoded so far=$framesEncoded, requesting keyframe")
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.STREAMING) }
        encoder?.requestKeyFrame()
    }

    override fun onDataStreamClosed(stream: AdbTransport.Stream) {
        synchronized(frameLock) { if (dataStream === stream) dataStream = null }
        logger.log("data stream closed; requests=$frameRequests sent=$framesSent encoded=$framesEncoded")
    }

    override fun onFrameRequested(stream: AdbTransport.Stream) {
        frameRequests++
        if (frameRequests <= 3 || frameRequests % 100 == 0L) logger.log("frame request #$frameRequests (queued=${pullQueue.size})")
        synchronized(frameLock) {
            // CarbitLink blocks until the encoder has a frame instead of answering
            // "none", so hold the request and answer when the next frame lands.
            val next = pullQueue.poll()
            if (next == null) {
                pendingRequests++
                encoder?.requestKeyFrame()
                scheduleStarvationCheck(stream)
                return
            }
            sendFrameLocked(stream, next)
        }
    }

    private val watchdog = android.os.Handler(android.os.Looper.getMainLooper())

    /** If the encoder stays silent, answer with the last keyframe so the car never times out. */
    private fun scheduleStarvationCheck(stream: AdbTransport.Stream) {
        watchdog.postDelayed({
            synchronized(frameLock) {
                val key = lastKeyFrame
                if (pendingRequests > 0 && dataStream === stream && key != null) {
                    pendingRequests--
                    sendFrameLocked(stream, key)
                }
            }
        }, 80)
    }

    /** Caller holds [frameLock]. Codec config always precedes the first frame. */
    private fun sendFrameLocked(stream: AdbTransport.Stream, frame: ByteArray) {
        val cfg = codecConfig
        if (!configSent && cfg != null) {
            stream.write(MirrorDataChannel.frame(cfg) + MirrorDataChannel.frame(frame))
            configSent = true
        } else {
            stream.write(MirrorDataChannel.frame(frame))
        }
        framesSent++
        if (framesSent <= 3 || framesSent % 300 == 0L) logger.log("frame sent #$framesSent (${frame.size} bytes)")
    }

    private fun onEncodedFrame(data: ByteArray, isConfig: Boolean, isKey: Boolean) {
        framesEncoded++
        if (framesEncoded <= 2) logger.log("encoder output #$framesEncoded config=$isConfig key=$isKey ${data.size} bytes")
        val pull = (config?.wantFps ?: 0) == 0
        synchronized(frameLock) {
            if (isConfig) {
                codecConfig = data
                configSent = false
                return
            }
            if (isKey) lastKeyFrame = data
            val stream = dataStream ?: return
            // The car's decoder needs to start on a keyframe.
            if (waitingForKey) {
                if (!isKey) { encoder?.requestKeyFrame(); return }
                waitingForKey = false
            }
            if (pull) {
                if (pendingRequests > 0) {
                    pendingRequests--
                    sendFrameLocked(stream, data)
                    return
                }
                if (pullQueue.size >= MAX_PULL_QUEUE) {
                    if (!isKey) { encoder?.requestKeyFrame(); return }
                    pullQueue.clear()
                }
                pullQueue.add(data)
                return
            }
            if (stream.backlogBytes() > MAX_PUSH_BACKLOG) {
                if (!isKey) { encoder?.requestKeyFrame(); return }
                stream.clearBacklog()
            }
            sendFrameLocked(stream, data)
        }
    }

    companion object {
        private const val TAG = "CarSession"
        private const val DEFAULT_BITRATE = 3_000_000
        private const val APPSTATUS_BACKGROUND = 0x20030
        private const val MAX_PULL_QUEUE = 8
        private const val MAX_PUSH_BACKLOG = 2 * 1024 * 1024
    }
}
