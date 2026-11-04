package dev.caraalink.video

import android.view.Surface

/** Common shape [CarVideoEncoder] (hardware MediaCodec) and [CarVideoEncoderX264] (software libx264) both implement, so `CarSession` can use either interchangeably behind `AppSettings.videoUseX264`. */
interface VideoEncoder {
    val width: Int
    val height: Int
    val inputSurface: Surface
    fun start()
    fun requestKeyFrame()
    fun release()
}
