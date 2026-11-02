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
    /**
     * What we told AA to pad the frame with (see AaServiceDiscovery's
     * autoMargin()) -- confirmed from mrmees/open-android-auto's own docs
     * (docs/video-resolution.md, GPL-3.0, facts only): the phone renders
     * its UI into a CENTERED sub-region of the frame and fills the margin
     * itself with black, and the head unit is expected to crop that black
     * out, not just scale the whole (content+black) frame to fit. We were
     * doing the latter -- scaling the full frame shrinks the actual
     * content more than necessary, which is the "could be sized bigger"
     * extra black space reported live. Zero when autoCrop/margins aren't
     * in use (e.g. FILL mode, which sends no margin at all).
     */
    private val widthMargin: Int = 0,
    private val heightMargin: Int = 0,
    private val onExit: () -> Unit,
    private val onSurface: (Surface?) -> Unit,
    private val onTouch: (pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit,
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
        // Multi-touch (pinch/rotate): AA's TouchEvent.touch_location is a
        // repeated field (confirmed from mrmees/open-android-auto's
        // docs/protocol-reference.md, GPL-3.0, field numbers only) -- every
        // currently-down finger goes in one message, with actionIndex saying
        // which one triggered this particular down/up/move.
        var lastMoveAt = 0L
        texture.setOnTouchListener { v, ev ->
            val action = when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 1
                MotionEvent.ACTION_MOVE -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastMoveAt < 30) return@setOnTouchListener true
                    lastMoveAt = now
                    2
                }
                else -> return@setOnTouchListener true
            }
            val pointers = (0 until ev.pointerCount).map { i ->
                val x = (ev.getX(i) / v.width * videoWidth).toInt().coerceIn(0, videoWidth - 1)
                val y = (ev.getY(i) / v.height * videoHeight).toInt().coerceIn(0, videoHeight - 1)
                Triple(x, y, ev.getPointerId(i))
            }
            onTouch(pointers, ev.actionIndex, action)
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
            // With a margin in use, the real content region is smaller than
            // the full frame -- scale against THAT so the content (not the
            // content-plus-AA's-own-black-padding) fills the available
            // space; the margin portion then extends past the screen edge
            // and gets clipped by area.clipChildren, cropping the black out
            // exactly as the protocol doc describes.
            val contentWidth = (videoWidth - widthMargin).coerceAtLeast(1)
            val contentHeight = (videoHeight - heightMargin).coerceAtLeast(1)
            val scale = if (dev.caraalink.settings.AppSettings.aaFillMode(context) == dev.caraalink.settings.AppSettings.AaFillMode.FILL) {
                maxOf(aw.toFloat() / contentWidth, ah.toFloat() / contentHeight)
            } else {
                minOf(aw.toFloat() / contentWidth, ah.toFloat() / contentHeight)
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
