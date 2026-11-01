package dev.caraalink.aa

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup

/**
 * On-phone preview of the AA receiver's video channel -- shows exactly what
 * the (not-yet-wired) CarbitLink video path would receive, without needing
 * the car. Starts [AaSessionProbe] if one isn't already running and renders
 * its `videoSink` frames to a [SurfaceView] via [AaVideoDecoder].
 */
class AaPreviewActivity : Activity() {

    private var decoder: AaVideoDecoder? = null
    private var probe: AaSessionProbe? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surfaceView = SurfaceView(this)
        setContentView(surfaceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val config = AaServiceDiscovery.Config()

        // Map this view's own pixels onto the AA video's coordinate space
        // (the resolution we told the phone we're rendering at) and forward
        // as touch events on the input channel -- lets this screen stand in
        // for the car head unit's touchscreen.
        surfaceView.setOnTouchListener { view, event ->
            val x = (event.x / view.width * config.resolution.width).toInt().coerceIn(0, config.resolution.width - 1)
            val y = (event.y / view.height * config.resolution.height).toInt().coerceIn(0, config.resolution.height - 1)
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_UP -> 1
                MotionEvent.ACTION_MOVE -> 2
                else -> return@setOnTouchListener false
            }
            probe?.sendTouch(x, y, action)
            true
        }

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                val dec = AaVideoDecoder(config.resolution.width, config.resolution.height)
                dec.start(holder.surface)
                decoder = dec

                val existing = AaSessionProbe.active
                val target = existing ?: AaSessionProbe(this@AaPreviewActivity).also { it.start() }
                target.videoSink = { ts, nal -> decoder?.feed(ts, nal) }
                probe = target
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                probe?.videoSink = null
                decoder?.stop()
                decoder = null
            }
        })
    }
}
