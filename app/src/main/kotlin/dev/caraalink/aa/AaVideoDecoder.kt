package dev.caraalink.aa

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Minimal H.264 Annex-B decoder for the AA video channel's raw NAL bytes,
 * rendering straight to a [Surface]. Standard [MediaCodec] usage (not part
 * of any reverse-engineered protocol) -- each [feed] call is one AA
 * `MEDIA_MESSAGE_DATA` frame's payload after the 8-byte timestamp is
 * stripped (see [AaSessionProbe.videoSink]). A frame containing inline
 * SPS/PPS + IDR NALs (AA's first frame) initializes the decoder without
 * needing CSD buffers, same as any Annex-B keyframe.
 *
 * Async mode (MediaCodec.Callback on its own [HandlerThread]) so each frame
 * renders the instant it's decoded instead of waiting for the next [feed]
 * call to drain output -- contributed upstream (see PR #21) after a
 * confirmed "video feels a beat behind" symptom.
 */
class AaVideoDecoder(private val width: Int, private val height: Int) {

    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var freeInputs = LinkedBlockingQueue<Int>()
    private var feedCount = 0

    @Synchronized
    fun start(surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val inputs = LinkedBlockingQueue<Int>()
        val t = HandlerThread("AaVideoDecoder").also { it.start() }
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                inputs.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                runCatching { codec.releaseOutputBuffer(index, true) }
                dev.caraalink.diagnostics.PerfStats.recordAaFrameRendered()
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                dev.caraalink.diagnostics.PerfStats.recordAaDecodeException()
                Log.w(TAG, "decoder error", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        }, Handler(t.looper))
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
        thread = t
        freeInputs = inputs
    }

    /**
     * Diagnostics only, added after an owner-reported "picture gets blocky"
     * symptom that a source comparison against PhoneAuto/LIVI could not
     * fully explain without actual receiver-side numbers (see
     * chatgpt-status.md "Priority 2"). Counting outcomes instead of
     * changing decode behavior: still submits access units in order, still
     * never drops one on purpose.
     */
    @Synchronized
    fun feed(timestampUs: Long, nal: ByteArray) {
        val c = codec ?: return
        dev.caraalink.diagnostics.PerfStats.recordAaFrame(nal.size)
        dev.caraalink.diagnostics.PerfStats.recordAaAccessUnit()
        try {
            val index = freeInputs.poll(10, TimeUnit.MILLISECONDS)
            if (index == null) {
                // Previously silent: the access unit was simply never
                // submitted to MediaCodec, no different from a dropped
                // frame, but with nothing anywhere to show it happened.
                dev.caraalink.diagnostics.PerfStats.recordAaInputBufferUnavailable()
                return
            }
            val buffer = c.getInputBuffer(index)
            if (buffer == null) {
                // Valid index but no buffer -- rarer than a plain
                // unavailable-buffer miss; still a dropped access unit,
                // not a successful submission.
                Log.w(TAG, "getInputBuffer($index) returned null -- access unit not submitted")
                dev.caraalink.diagnostics.PerfStats.recordAaDecodeException()
                return
            }
            buffer.clear()
            buffer.put(nal)
            c.queueInputBuffer(index, 0, nal.size, timestampUs, 0)
            dev.caraalink.diagnostics.PerfStats.recordAaSubmitted()
        } catch (e: Exception) {
            dev.caraalink.diagnostics.PerfStats.recordAaDecodeException()
            Log.w(TAG, "feed failed", e)
        }
        // Low-rate summary (every ~150 frames, ~5s at 30fps) so the counts
        // are visible even with the debug HUD off, without logging every
        // single frame.
        feedCount++
        if (feedCount % 150 == 0) {
            Log.i(TAG, dev.caraalink.diagnostics.PerfStats.aaDecodeSummary())
        }
    }

    @Synchronized
    fun stop() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val TAG = "AaVideoDecoder"
    }
}
