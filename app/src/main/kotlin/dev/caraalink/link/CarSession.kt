package dev.caraalink.link

import android.content.Context
import android.graphics.Color
import android.hardware.usb.UsbAccessory
import android.media.projection.MediaProjection
import android.util.Log
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder
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
        fun openAndroidAuto()
        fun requestScreenCapture()
    }

    private val logger = DiagnosticLogger(context, "car-session")
    private var transport: AdbTransport? = null
    private var config: CaptureConfig? = null
    private var encoder: CarVideoEncoder? = null
    private val display = CarDisplay(context, listOf(
        CarHomePresentation.Tile("Android Auto", "▲", Color.parseColor("#1E8E3E")) { openAndroidAuto() },
        CarHomePresentation.Tile("Phone screen", "▣", Color.parseColor("#1A73E8")) { openMirror() },
        CarHomePresentation.Tile("Settings", "⚙", Color.parseColor("#5F6368")) { showSettings() },
    ))

    private val frameLock = Any()
    @Volatile private var dataStream: AdbTransport.Stream? = null
    private var codecConfig: ByteArray? = null
    private var configSent = false
    private val pullQueue = ArrayDeque<ByteArray>()
    private var framesSent = 0L

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
        val pxc = PxcChannel(logger) { info -> onClientInfo(info) }
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
        display.setProjection(projection)
        logger.log("Screen capture granted")
    }

    private fun release() {
        weatherThread?.interrupt()
        weatherThread = null
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

    private fun openAndroidAuto() {
        phone.openAndroidAuto()
        if (!display.showMirror()) askForCapture() else display.setStatus("Android Auto (phone screen)")
    }

    private fun openMirror() {
        if (!display.showMirror()) askForCapture()
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
        encoder?.release()
        val fps = if (config.wantFps > 0) config.wantFps else 30
        val bitRate = if (config.bitRate > 0) config.bitRate else DEFAULT_BITRATE
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
            pullQueue.clear()
        }
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.STREAMING) }
        encoder?.requestKeyFrame()
    }

    override fun onDataStreamClosed(stream: AdbTransport.Stream) {
        synchronized(frameLock) { if (dataStream === stream) dataStream = null }
    }

    override fun onFrameRequested(stream: AdbTransport.Stream) {
        val out = synchronized(frameLock) {
            val cfg = codecConfig
            val next = pullQueue.poll()
            when {
                next == null -> MirrorDataChannel.NO_FRAME
                !configSent && cfg != null -> { configSent = true; MirrorDataChannel.frame(cfg) + MirrorDataChannel.frame(next) }
                else -> MirrorDataChannel.frame(next)
            }
        }
        stream.write(out)
        if (out !== MirrorDataChannel.NO_FRAME) framesSent++
    }

    private fun onEncodedFrame(data: ByteArray, isConfig: Boolean, isKey: Boolean) {
        val pull = (config?.wantFps ?: 0) == 0
        synchronized(frameLock) {
            if (isConfig) {
                codecConfig = data
                configSent = false
                return
            }
            val stream = dataStream ?: return
            if (pull) {
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
            val cfg = codecConfig
            if (!configSent && cfg != null) {
                stream.write(MirrorDataChannel.frame(cfg))
                configSent = true
            }
            stream.write(MirrorDataChannel.frame(data))
            framesSent++
        }
    }

    companion object {
        private const val TAG = "CarSession"
        private const val DEFAULT_BITRATE = 3_000_000
        private const val MAX_PULL_QUEUE = 8
        private const val MAX_PUSH_BACKLOG = 2 * 1024 * 1024
    }
}
