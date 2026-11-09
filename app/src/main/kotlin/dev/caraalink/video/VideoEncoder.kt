package dev.caraalink.video

import android.view.Surface

/** Shape [CarVideoEncoder] (hardware MediaCodec) implements. */
interface VideoEncoder {
    val width: Int
    val height: Int
    val inputSurface: Surface
    fun start()
    fun requestKeyFrame()
    fun release()
}
