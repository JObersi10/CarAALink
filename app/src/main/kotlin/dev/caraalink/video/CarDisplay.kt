package dev.caraalink.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import dev.caraalink.accessibility.HeadUnitServerAccessibilityService

/**
 * Owns what gets drawn into the encoder's surface: our own [CarHomePresentation]
 * (no permission needed) or a MediaProjection mirror of the phone screen.
 * All work happens on the main thread.
 */
class CarDisplay(
    private val context: Context,
    private val tiles: List<CarHomePresentation.Tile>,
) {
    enum class Mode { HOME, MIRROR }

    private val main = Handler(Looper.getMainLooper())
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var homeDisplay: VirtualDisplay? = null
    private var presentation: CarHomePresentation? = null
    private var projection: MediaProjection? = null
    private var mirrorDisplay: VirtualDisplay? = null
    @Volatile var mode = Mode.HOME
        private set
    private var status = "Connected"
    private var info = ""
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** Point the displays at a (new) encoder surface of [w]x[h]. */
    fun attach(newSurface: Surface, w: Int, h: Int) = main.post {
        surface = newSurface
        width = w
        height = h
        val dpi = (minOf(w, h) / 720f * 213).toInt().coerceIn(120, 320)
        val home = homeDisplay
        if (home == null) {
            val dm = context.getSystemService(DisplayManager::class.java)
            homeDisplay = dm.createVirtualDisplay("CarAALink-home", w, h, dpi, null, 0)
        } else {
            home.resize(w, h, dpi)
        }
        presentation?.dismiss()
        presentation = try {
            CarHomePresentation(context, homeDisplay!!.display, tiles).also {
                it.show()
                it.setStatus(status)
                it.setInfo(info)
            }
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "presentation failed", e)
            null
        }
        mirrorDisplay?.resize(w, h, dpi)
        applyMode()
    }

    fun setStatus(text: String) = main.post {
        status = text
        presentation?.setStatus(text)
    }

    fun setInfo(text: String) = main.post {
        info = text
        presentation?.setInfo(text)
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
                        showHome()
                    }
                }
            }
        }, main)
    }

    fun showHome() = main.post {
        mode = Mode.HOME
        applyMode()
    }

    /** Returns false when there's no screen-capture permission yet. */
    fun showMirror(): Boolean {
        if (projection == null) return false
        main.post {
            mode = Mode.MIRROR
            applyMode()
        }
        return true
    }

    private fun applyMode() {
        val s = surface ?: return
        if (mode == Mode.MIRROR && projection != null) {
            homeDisplay?.surface = null
            val existing = mirrorDisplay
            if (existing == null) {
                val dpi = context.resources.displayMetrics.densityDpi
                // One createVirtualDisplay per projection on Android 14+, so keep it and swap surfaces.
                mirrorDisplay = projection!!.createVirtualDisplay(
                    "CarAALink-mirror", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, s, null, null,
                )
            } else {
                existing.surface = s
            }
        } else {
            mode = Mode.HOME
            mirrorDisplay?.surface = null
            homeDisplay?.surface = s
        }
    }

    /**
     * Car touch in encoder-pixel space. On the home screen it goes straight to
     * our views; in mirror mode it's replayed on the phone via the
     * accessibility service (taps and swipes only).
     */
    fun touch(action: Int, x: Float, y: Float) = main.post {
        if (mode == Mode.HOME) {
            val view = presentation?.window?.decorView ?: return@post
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downAt = now
            val ev = MotionEvent.obtain(downAt, now, action, x, y, 0)
            view.dispatchTouchEvent(ev)
            ev.recycle()
            return@post
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x; downY = y; downAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val a11y = HeadUnitServerAccessibilityService.instance ?: return@post
                val metrics = context.resources.displayMetrics
                val sx = metrics.widthPixels.toFloat() / width
                val sy = metrics.heightPixels.toFloat() / height
                val ms = (SystemClock.uptimeMillis() - downAt).coerceIn(30, 1500)
                a11y.dispatchStroke(downX * sx, downY * sy, x * sx, y * sy, ms)
            }
        }
    }

    fun release() = main.post {
        presentation?.dismiss()
        presentation = null
        homeDisplay?.release()
        homeDisplay = null
        mirrorDisplay?.release()
        mirrorDisplay = null
        projection?.stop()
        projection = null
        surface = null
    }

    companion object {
        private const val TAG = "CarDisplay"
    }
}
