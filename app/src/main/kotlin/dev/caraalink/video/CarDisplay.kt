package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import dev.caraalink.accessibility.HeadUnitServerAccessibilityService

/**
 * Owns what the car sees. One private virtual display (never seen by DeX)
 * renders into the encoder's surface and shows either the home screen or the
 * mirror view; the phone screen itself comes from a MediaProjection
 * AUTO_MIRROR display drawn into the mirror view's TextureView.
 * All work happens on the main thread.
 */
class CarDisplay(
    private val context: Context,
    private val tiles: List<CarHomePresentation.Tile>,
    private val onCarHome: () -> Unit,
) {
    enum class Mode { HOME, MIRROR }

    private val main = Handler(Looper.getMainLooper())
    private var carDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var projection: MediaProjection? = null
    private var mirrorDisplay: VirtualDisplay? = null
    @Volatile var mode = Mode.HOME
        private set
    private var status = "Connected"
    private var info = ""
    private var touchDownAt = 0L

    /** Point the car display at a (new) encoder surface of [w]x[h]. */
    fun attach(surface: Surface, w: Int, h: Int) = main.post {
        val dpi = (minOf(w, h) / 720f * 213).toInt().coerceIn(120, 320)
        val existing = carDisplay
        if (existing == null) {
            val dm = context.getSystemService(DisplayManager::class.java)
            carDisplay = dm.createVirtualDisplay("CarAALink", w, h, dpi, surface, 0)
        } else {
            existing.resize(w, h, dpi)
            existing.surface = surface
        }
        showCurrent()
    }

    fun setStatus(text: String) = main.post {
        status = text
        (presentation as? CarHomePresentation)?.setStatus(text)
    }

    fun setInfo(text: String) = main.post {
        info = text
        (presentation as? CarHomePresentation)?.setInfo(text)
    }

    fun hasProjection() = projection != null

    fun setProjection(p: MediaProjection) = main.post {
        projection?.stop()
        mirrorDisplay?.release()
        mirrorDisplay = null
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (projection === p) {
                        projection = null
                        mirrorDisplay?.release()
                        mirrorDisplay = null
                        if (mode == Mode.MIRROR) showHome()
                    }
                }
            }
        }, main)
    }

    fun showHome() = main.post {
        mode = Mode.HOME
        showCurrent()
    }

    /** Returns false when there's no screen-capture permission yet. */
    fun showMirror(): Boolean {
        if (projection == null) return false
        main.post {
            mode = Mode.MIRROR
            showCurrent()
        }
        return true
    }

    private fun showCurrent() {
        val display = carDisplay?.display ?: return
        presentation?.dismiss()
        presentation = try {
            when {
                mode == Mode.MIRROR && projection != null -> mirrorPresentation(display)
                else -> {
                    mode = Mode.HOME
                    mirrorDisplay?.surface = null
                    CarHomePresentation(context, display, tiles).also {
                        it.show()
                        it.setStatus(status)
                        it.setInfo(info)
                    }
                }
            }.also { if (it !is CarHomePresentation) it.show() }
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "presentation failed", e)
            null
        }
    }

    private fun mirrorPresentation(display: Display): Presentation {
        val phone = phoneSize()
        // Half resolution is plenty for a 720p-tall car screen and halves encoder load.
        val mw = phone.x / 2
        val mh = phone.y / 2
        return MirrorPresentation(
            context, display, mw, mh,
            onHome = { showHome() },
            onCarHome = onCarHome,
            onSurface = { s -> attachMirror(s, mw, mh) },
            onPhoneStroke = { x1, y1, x2, y2, ms -> replayOnPhone(x1, y1, x2, y2, ms) },
        )
    }

    private fun attachMirror(surface: Surface?, w: Int, h: Int) {
        val p = projection ?: return
        val existing = mirrorDisplay
        if (existing != null) {
            existing.surface = surface
            return
        }
        if (surface == null) return
        val dpi = context.resources.displayMetrics.densityDpi / 2
        // One createVirtualDisplay per projection on Android 14+, so keep it and swap surfaces.
        // AUTO_MIRROR = a copy of the phone screen, not a second display (no DeX).
        mirrorDisplay = p.createVirtualDisplay(
            "CarAALink-mirror", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
        )
    }

    private fun replayOnPhone(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val a11y = HeadUnitServerAccessibilityService.instance ?: run {
            Log.w(TAG, "touch dropped: accessibility service is off")
            return
        }
        val phone = phoneSize()
        a11y.dispatchStroke(x1 * phone.x, y1 * phone.y, x2 * phone.x, y2 * phone.y, ms)
    }

    @Suppress("DEPRECATION")
    private fun phoneSize(): Point {
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return Point().also { d.getRealSize(it) }
    }

    /** Car touch in car-display pixels, delivered to whatever is on screen. */
    fun touch(action: Int, x: Float, y: Float) = main.post {
        val view = presentation?.window?.decorView ?: return@post
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownAt = now
        val ev = MotionEvent.obtain(touchDownAt, now, action, x, y, 0)
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    fun release() = main.post {
        presentation?.dismiss()
        presentation = null
        carDisplay?.release()
        carDisplay = null
        mirrorDisplay?.release()
        mirrorDisplay = null
        projection?.stop()
        projection = null
    }

    companion object {
        private const val TAG = "CarDisplay"
    }
}
