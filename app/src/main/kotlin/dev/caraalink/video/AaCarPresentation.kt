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
    private val touchLogger = dev.caraalink.diagnostics.DiagnosticLogger(context, "aa-touch")

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
        // Ghost swipes on a plain tap, reported live on the car -- the car's
        // own touch stack is confirmed (from a car-side log) to always fall
        // back to its degraded "app inner touch" software path rather than
        // real HID touch ("registerHid fail... system touch not supported",
        // seen on every session, including with the stock dongle). We
        // forward whatever DOWN/MOVE/UP sequence the car reports straight
        // into AA's own touch protocol with zero filtering -- a real HID
        // touch controller debounces its own jitter; this software fallback
        // may not, so a tap's natural sub-pixel wobble becomes a real MOVE
        // message AA sees as the start of a drag. Standard Android touch-
        // slop fix: lock each pointer's reported position to where it went
        // down until it has genuinely moved past a threshold, so jitter
        // under that threshold never reaches AA as motion.
        val downPos = mutableMapOf<Int, Pair<Int, Int>>()
        val slopExceeded = mutableSetOf<Int>()
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
            // AA expects touch already remapped into the CONTENT region's
            // own coordinate space (0..contentWidth, 0..contentHeight), not
            // the full frame -- confirmed from mrmees/open-android-auto's
            // docs/video-resolution.md (GPL-3.0, facts only): "Touches in
            // the content region map to full AA coordinate space
            // (0-remoteWidth x 0-remoteHeight)", remoteWidth/Height being
            // the content dimensions, margins split evenly. We were sending
            // full-FRAME coordinates (margin baked in) unchanged -- off by
            // exactly half the margin on whichever axis has one, which is
            // exactly the "touch registers lower" symptom (half of a 360px
            // heightMargin = 180px low). Owner confirms this part is fixed.
            val contentWidth = (videoWidth - widthMargin).coerceAtLeast(1)
            val contentHeight = (videoHeight - heightMargin).coerceAtLeast(1)
            val pointers = (0 until ev.pointerCount).map { i ->
                val fullX = (ev.getX(i) / v.width * videoWidth).toInt()
                val fullY = (ev.getY(i) / v.height * videoHeight).toInt()
                var x = (fullX - widthMargin / 2).coerceIn(0, contentWidth - 1)
                var y = (fullY - heightMargin / 2).coerceIn(0, contentHeight - 1)
                val pid = ev.getPointerId(i)
                if (action == 0) {
                    downPos[pid] = x to y
                    slopExceeded.remove(pid)
                } else if (pid !in slopExceeded) {
                    val (dx0, dy0) = downPos[pid] ?: (x to y)
                    val dist = kotlin.math.hypot((x - dx0).toDouble(), (y - dy0).toDouble())
                    if (dist > TOUCH_SLOP_PX) {
                        slopExceeded.add(pid)
                        touchLogger.log("pointer $pid became a drag at ${"%.1f".format(dist)}px (slop=$TOUCH_SLOP_PX)")
                    } else {
                        // Still within jitter range of the down point -- report it as
                        // not having moved at all rather than relaying the wobble.
                        x = dx0
                        y = dy0
                    }
                }
                Triple(x, y, pid)
            }
            if (action == 1) {
                val upPid = ev.getPointerId(ev.actionIndex)
                downPos.remove(upPid)
                slopExceeded.remove(upPid)
            }
            // Owner reports touch registering lower than where they press,
            // for AA specifically -- the render/crop math checks out on
            // paper (touch and render use the identical scale+offset), so
            // rather than guess again, log the actual numbers: raw view
            // coords, view size, and the resulting video-pixel coords. Next
            // capture, compare the logged (x,y) for a tap on a known AA
            // button against that button's real on-screen position.
            if (action == 0) {
                touchLogger.log("DOWN rawX=${ev.getX(0)} rawY=${ev.getY(0)} viewW=${v.width} viewH=${v.height} " +
                    "videoW=$videoWidth videoH=$videoHeight widthMargin=$widthMargin heightMargin=$heightMargin " +
                    "-> mappedX=${pointers[0].first} mappedY=${pointers[0].second}")
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

    companion object {
        // In mapped/video-space pixels. Generous on purpose -- AA's own
        // tap-vs-drag threshold on the phone side isn't confirmed, and
        // false "still a tap" is cheap (worst case a real short drag's
        // first couple pixels get eaten) while false "that was a drag" is
        // the ghost-swipe bug this exists to kill.
        private const val TOUCH_SLOP_PX = 18
    }
}
