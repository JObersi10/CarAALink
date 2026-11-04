package dev.caraalink.aa

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

/**
 * Minimal H.264 Annex-B decoder for the AA video channel's raw NAL bytes,
 * rendering straight to a [Surface]. Standard [MediaCodec] usage (not part
 * of any reverse-engineered protocol) -- each [feed] call is one AA
 * `MEDIA_MESSAGE_DATA` frame's payload after the 8-byte timestamp is
 * stripped (see [AaSessionProbe.videoSink]). A frame containing inline
 * SPS/PPS + IDR NALs (AA's first frame) initializes the decoder without
 * needing CSD buffers, same as any Annex-B keyframe.
 */
class AaVideoDecoder(private val width: Int, private val height: Int) {

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var feedCount = 0

    @Synchronized
    fun start(surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
    }

    /**
     * Diagnostics only, added after an owner-reported "picture gets blocky"
     * symptom that a source comparison against PhoneAuto/LIVI could not
     * fully explain without actual receiver-side numbers (see
     * chatgpt-status.md "Priority 2"). Before this, a missing input buffer
     * (the `inIndex < 0` branch) silently skipped the access unit -- no
     * count, no log, indistinguishable from a normal successful decode.
     * Counting outcomes instead of changing decode behavior: still submits
     * access units in order, still never drops one on purpose.
     */
    @Synchronized
    fun feed(timestampUs: Long, nal: ByteArray) {
        val c = codec ?: return
        dev.caraalink.diagnostics.PerfStats.recordAaFrame(nal.size)
        dev.caraalink.diagnostics.PerfStats.recordAaAccessUnit()
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex)
                if (buffer == null) {
                    // Valid index but no buffer -- rarer than a plain
                    // unavailable-buffer miss; still a dropped access unit,
                    // not a successful submission.
                    Log.w(TAG, "getInputBuffer($inIndex) returned null -- access unit not submitted")
                    dev.caraalink.diagnostics.PerfStats.recordAaDecodeException()
                } else {
                    buffer.clear()
                    buffer.put(nal)
                    c.queueInputBuffer(inIndex, 0, nal.size, timestampUs, 0)
                    dev.caraalink.diagnostics.PerfStats.recordAaSubmitted()
                }
            } else {
                // Previously silent: the access unit was simply never
                // submitted to MediaCodec, no different from a dropped
                // frame, but with nothing anywhere to show it happened.
                dev.caraalink.diagnostics.PerfStats.recordAaInputBufferUnavailable()
            }
            var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, true)
                dev.caraalink.diagnostics.PerfStats.recordAaFrameRendered()
                outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }
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
    }

    companion object {
        private const val TAG = "AaVideoDecoder"
    }
}
