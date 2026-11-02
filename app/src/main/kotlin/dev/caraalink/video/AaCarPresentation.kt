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
        area.addView(texture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val aw = r - l
            val ah = b - t
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            val scale = minOf(aw.toFloat() / videoWidth, ah.toFloat() / videoHeight)
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
        // Small back button: tap = Back, hold = exit AA session to CarAALink home.
        // No visible back button here -- Android Auto has its own in-app
        // back/exit chrome, a second one of ours was redundant and could
        // overlap it. Just a long-press-only corner to get back to the
        // CarAALink home screen, since there needs to be *some* way out.
        root.addView(FrameLayout(context).apply {
            isClickable = true
            setOnLongClickListener { onExit(); true }
        }, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.START))
        dev.caraalink.diagnostics.PerfStats.attachHud(context, root)
        setContentView(root)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()
}
