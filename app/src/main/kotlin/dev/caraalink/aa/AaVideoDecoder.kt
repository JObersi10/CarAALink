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

    @Synchronized
    fun start(surface: Surface) {
        stop()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, surface, null, 0)
        c.start()
        codec = c
    }

    @Synchronized
    fun feed(timestampUs: Long, nal: ByteArray) {
        val c = codec ?: return
        dev.caraalink.diagnostics.PerfStats.recordAaFrame(nal.size)
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex) ?: return
                buffer.clear()
                buffer.put(nal)
                c.queueInputBuffer(inIndex, 0, nal.size, timestampUs, 0)
            }
            var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                c.releaseOutputBuffer(outIndex, true)
                outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w("AaVideoDecoder", "feed failed", e)
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
}
