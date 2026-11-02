package dev.caraalink.aa

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import dev.caraalink.R
import dev.caraalink.settings.AppSettings
import dev.caraalink.video.CarHomePresentation
import dev.caraalink.video.buildHomeContent

/**
 * On-phone stand-in for the car screen, for testing without the car: opens
 * on CarAALink's own home screen (same view-building code the real car
 * Presentation uses, via [buildHomeContent]) and only starts the actual AA
 * session -- [AaSessionProbe] plus a decoded, interactive video view -- once
 * the "Android Auto" tile is tapped, same as it works on the car.
 */
class AaPreviewActivity : Activity() {

    private var decoder: AaVideoDecoder? = null
    private var probe: AaSessionProbe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    private fun showHome() {
        val home = buildHomeContent(
            this,
            listOf(CarHomePresentation.Tile("Android Auto", R.drawable.ic_tile_android_auto, Color.parseColor("#1E8E3E")) { showAaSession() }),
        )
        setContentView(home.root)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showAaSession() {
        // Must match what AaSessionProbe actually negotiates with the phone
        // (also configFromSettings) -- a decoder configured for a different
        // size than what's really arriving silently fails to produce frames.
        val config = AaServiceDiscovery.configFromSettings(this)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))

        root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val targetW = config.resolution.width.toFloat()
            val targetH = config.resolution.height.toFloat()
            val availW = v.width
            val availH = v.height
            if (availW <= 0 || availH <= 0) return@addOnLayoutChangeListener
            val scale = if (AppSettings.aaFillMode(this) == AppSettings.AaFillMode.FILL) {
                maxOf(availW / targetW, availH / targetH)
            } else {
                minOf(availW / targetW, availH / targetH)
            }
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
                target.attachVideoSink { ts, nal -> decoder?.feed(ts, nal) }
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
                text = "Back to car home"
                setOnClickListener { showHome() }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )
        dev.caraalink.diagnostics.PerfStats.attachHud(this, root)

        setContentView(root)
    }

    override fun onBackPressed() {
        finish()
    }
}
