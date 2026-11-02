package dev.caraalink.aa

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Plays the AA media-sink audio channel's AAC-LC stream out the phone's own
 * speaker (or whatever `AudioManager` currently routes to -- same device
 * Android would pick for any other app's audio). Without this the channel
 * negotiates and streams fine but nothing is ever done with the bytes, which
 * is why AA audio was silent regardless of Bluetooth routing: playback was
 * simply never implemented, not a routing bug.
 *
 * AAC-LC needs its AudioSpecificConfig (the codec-config bytes, 2 bytes for
 * the plain LC case) as `csd-0` before the first real frame -- standard
 * MediaCodec usage, not part of the AA protocol itself.
 */
class AaAudioPlayer(private val sampleRate: Int, private val channelCount: Int) {

    companion object {
        private const val TAG = "AaAudioPlayer"
    }

    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    @Synchronized
    fun setCodecConfig(csd0: ByteArray) {
        stop()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        }
        val c = runCatching {
            MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, 0)
                start()
            }
        }.onFailure { Log.w(TAG, "decoder init failed", it) }.getOrNull() ?: return

        val channelMask = if (channelCount >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf.coerceAtLeast(4096) * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()

        codec = c
        track = t
    }

    @Synchronized
    fun feed(aacFrame: ByteArray) {
        val c = codec ?: return
        val t = track ?: return
        try {
            val inIndex = c.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val buffer = c.getInputBuffer(inIndex) ?: return
                buffer.clear()
                buffer.put(aacFrame)
                c.queueInputBuffer(inIndex, 0, aacFrame.size, 0, 0)
            }
            var outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                val out = c.getOutputBuffer(outIndex)
                if (out != null && bufferInfo.size > 0) {
                    val pcm = ByteArray(bufferInfo.size)
                    out.get(pcm)
                    t.write(pcm, 0, pcm.size)
                }
                c.releaseOutputBuffer(outIndex, false)
                outIndex = c.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "feed failed", e)
        }
    }

    @Synchronized
    fun stop() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
    }
}
