package dev.caraalink.aa

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout

/**
 * On-phone preview of the AA receiver's video channel -- shows exactly what
 * the (not-yet-wired) CarbitLink video path would receive, without needing
 * the car. Starts [AaSessionProbe] if one isn't already running and renders
 * its `videoSink` frames to a [SurfaceView] via [AaVideoDecoder].
 *
 * The video is a fixed 16:9-family resolution ([AaServiceDiscovery]) that
 * never matches a phone's own (much taller) aspect ratio, so the
 * [SurfaceView] is explicitly letterboxed to 16:9 and centered rather than
 * stretched to fill the screen.
 */
class AaPreviewActivity : Activity() {

    private var decoder: AaVideoDecoder? = null
    private var probe: AaSessionProbe? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AaServiceDiscovery.Config()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))

        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val targetW = config.resolution.width.toFloat()
            val targetH = config.resolution.height.toFloat()
            val availW = v.width
            val availH = v.height
            if (availW <= 0 || availH <= 0) return@addOnLayoutChangeListener
            val scale = minOf(availW / targetW, availH / targetH)
            val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
            val newW = (targetW * scale).toInt()
            val newH = (targetH * scale).toInt()
            if (lp.width != newW || lp.height != newH) {
                lp.width = newW
                lp.height = newH
                surfaceView.layoutParams = lp
            }
        }

        // Map this view's own pixels onto the AA video's coordinate space
        // (the resolution we told the phone we're rendering at) and forward
        // as touch events on the input channel -- lets this screen stand in
        // for the car head unit's touchscreen. ACTION_MOVE is coalesced to
        // one send per frame tick rather than one per raw pointer-move
        // event: at full rate a swipe can fire dozens of MotionEvents before
        // the previous send even reaches the phone, which is what made touch
        // feel laggy (on top of the logging fix in AaTlsChannel).
        var lastMoveAt = 0L
        surfaceView.setOnTouchListener { view, event ->
            val x = (event.x / view.width * config.resolution.width).toInt().coerceIn(0, config.resolution.width - 1)
            val y = (event.y / view.height * config.resolution.height).toInt().coerceIn(0, config.resolution.height - 1)
            val action = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_UP -> 1
                MotionEvent.ACTION_MOVE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastMoveAt < 30) return@setOnTouchListener true
                    lastMoveAt = now
                    2
                }
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

                val target = AaSessionProbe.active ?: AaSessionProbe(this@AaPreviewActivity)
                target.videoSink = { ts, nal -> decoder?.feed(ts, nal) }
                probe = target
                if (AaSessionProbe.active == null) target.start()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                probe?.videoSink = null
                decoder?.stop()
                decoder = null
            }
        })

        root.addView(
            Button(this).apply {
                text = "Exit preview"
                setOnClickListener { finish() }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )

        setContentView(root)
    }

    override fun onBackPressed() {
        finish()
    }
}
