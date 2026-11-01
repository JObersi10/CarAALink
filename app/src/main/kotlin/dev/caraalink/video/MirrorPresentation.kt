package dev.caraalink.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
 * Car-side view while mirroring: a Home bar on the left and the phone screen,
 * letterboxed to the phone's aspect ratio. Touches on the phone area are
 * reported normalised (0..1) so they can be replayed on the real screen.
 */
class MirrorPresentation(
    context: Context,
    display: Display,
    private val phoneWidth: Int,
    private val phoneHeight: Int,
    private val onHome: () -> Unit,
    private val onCarHome: () -> Unit,
    private val onSurface: (Surface?) -> Unit,
    private val onPhoneStroke: (x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) -> Unit,
) : Presentation(context, display) {

    private var surface: Surface? = null
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val area = FrameLayout(context)
        val texture = TextureView(context)
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(phoneWidth, phoneHeight)
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
        texture.setOnTouchListener { v, ev ->
            val nx = (ev.x / v.width).coerceIn(0f, 1f)
            val ny = (ev.y / v.height).coerceIn(0f, 1f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = nx; downY = ny; downAt = ev.eventTime }
                MotionEvent.ACTION_UP -> onPhoneStroke(downX, downY, nx, ny, (ev.eventTime - downAt).coerceIn(30, 1500))
            }
            true
        }
        area.addView(texture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
        // Fit the phone's aspect ratio inside whatever space is left.
        area.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val aw = r - l
            val ah = b - t
            if (aw <= 0 || ah <= 0) return@addOnLayoutChangeListener
            val scale = minOf(aw.toFloat() / phoneWidth, ah.toFloat() / phoneHeight)
            val w = (phoneWidth * scale).toInt()
            val h = (phoneHeight * scale).toInt()
            val lp = texture.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w || lp.height != h) {
                lp.width = w
                lp.height = h
                texture.post { texture.layoutParams = lp }
            }
        }
        root.addView(area, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Small home button, like CarbitLink's: tap = CarAALink home, hold = car's own home.
        root.addView(TextView(context).apply {
            text = "⌂"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 31, 42, 48))
            }
            isClickable = true
            setOnClickListener { onHome() }
            setOnLongClickListener { onCarHome(); true }
        }, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), 0, 0)
        })
        setContentView(root)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics,
    ).toInt()
}
