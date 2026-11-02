package dev.caraalink.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Car-side view for a live Android Auto session: the decoded video,
 * letterboxed to its real resolution, with touches forwarded live
 * (down/move/up) rather than replayed as a synthesized stroke -- AA expects
 * real-time touch, not a single gesture like phone mirroring does.
 */
class AaCarPresentation(
    context: Context,
    display: Display,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val onExit: () -> Unit,
    private val onSurface: (Surface?) -> Unit,
    private val onTouch: (x: Int, y: Int, action: Int) -> Unit,
) : Presentation(context, display) {

    private var surface: Surface? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val area = FrameLayout(context)
        val texture = TextureView(context)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(videoWidth, videoHeight)
                surface = Surface(st).also(onSurface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                onSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        var lastMoveAt = 0L
        texture.setOnTouchListener { v, ev ->
            val x = (ev.x / v.width * videoWidth).toInt().coerceIn(0, videoWidth - 1)
            val y = (ev.y / v.height * videoHeight).toInt().coerceIn(0, videoHeight - 1)
            val action = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> 0
                MotionEvent.ACTION_UP -> 1
                MotionEvent.ACTION_MOVE -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastMoveAt < 30) return@setOnTouchListener true
                    lastMoveAt = now
                    2
                }
                else -> return@setOnTouchListener true
            }
            onTouch(x, y, action)
            true
        }
        area.clipChildren = true
        area.addView(texture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val aw = r - l
            val ah = b - t
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            // AA has no resolution matching CarbitLink's 2.67:1 panel (confirmed:
            // the car reports 1920x720, AA only offers 16:9-family sizes), so one
            // of these two tradeoffs is unavoidable. FIT (min) letterboxes/
            // pillarboxes with nothing cropped; FILL (max) covers the whole
            // panel and relies on `area.clipChildren` to cut off the overflow.
            val scale = if (dev.caraalink.settings.AppSettings.aaFillMode(context) == dev.caraalink.settings.AppSettings.AaFillMode.FILL) {
                maxOf(aw.toFloat() / videoWidth, ah.toFloat() / videoHeight)
            } else {
                minOf(aw.toFloat() / videoWidth, ah.toFloat() / videoHeight)
            }
            val w = (videoWidth * scale).toInt()
            val h = (videoHeight * scale).toInt()
            val lp = texture.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                texture.post { texture.layoutParams = lp }
            }
        }
        root.addView(area, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Visible, tappable "home" corner back to CarAALink's own home
        // screen. This used to be invisible (tap-blind, long-press-only)
        // on the theory that AA's own in-app back/exit chrome made a
        // second one redundant -- in practice that just made the only way
        // out of AA undiscoverable, since AA's own exit chrome is a
        // separate thing it draws inside the video, not something that
        // gets you back to CarAALink. Semi-transparent so it doesn't
        // block much of AA's own UI underneath it.
        root.addView(
            TextView(context).apply {
                text = "⌂"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(90, 0, 0, 0))
                isClickable = true
                setOnClickListener { onExit() }
            },
            FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                topMargin = dp(8)
                leftMargin = dp(8)
            },
        )
        dev.caraalink.diagnostics.PerfStats.attachHud(context, root)
        setContentView(root)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()
}
