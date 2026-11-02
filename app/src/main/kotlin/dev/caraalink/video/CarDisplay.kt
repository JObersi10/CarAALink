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
    private val onBack: () -> Unit,
    private val onExit: () -> Unit,
) {
    enum class Mode { HOME, MIRROR, AA }

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

    // phoneSize() is only read when (re)building the mirror presentation, so a
    // rotation mid-session otherwise stayed locked to whatever orientation the
    // phone was in when "Phone screen" was tapped -- the mirrored app's own
    // landscape content just got letterboxed smaller inside that fixed
    // portrait canvas instead of the canvas itself expanding. Watching the
    // phone's own (default) display for rotation and rebuilding on change is
    // what picks up the new aspect ratio.
    // onDisplayChanged fires for ANY property change on the default display
    // (brightness, refresh rate, etc.), not just rotation -- rebuilding the
    // presentation on every one of those caused a brief visible flash even
    // when the phone never actually rotated. Only rebuild when the phone's
    // own orientation genuinely flipped since the last time we checked.
    private var lastMirrorPortrait: Boolean? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || mode != Mode.MIRROR) return
            val phone = phoneSize()
            val portrait = phone.y >= phone.x
            if (lastMirrorPortrait != null && lastMirrorPortrait == portrait) return
            lastMirrorPortrait = portrait
            main.post { showCurrent() }
        }
    }

    init {
        context.getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
    }

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

    fun setProjection(p: MediaProjection, showMirrorNow: Boolean) = main.post {
        projection?.stop()
        mirrorDisplay?.release()
        mirrorDisplay = null
        projection = p
        // Show the mirror in the same main-thread step the projection lands, so
        // the user's "Phone screen" tap takes effect without a second tap.
        if (showMirrorNow) {
            mode = Mode.MIRROR
            showCurrent()
        }
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
        // Leaving an active mirror session used to just swap the on-screen
        // Presentation -- the underlying MediaProjection (and the system's
        // "screen is being recorded" capture, battery drain included) kept
        // running in the background indefinitely, only ever actually
        // stopped by release() (the whole car-session teardown). Stop it
        // for real when genuinely leaving mirror, not just hiding it.
        if (mode == Mode.MIRROR) {
            projection?.stop()
            projection = null
            mirrorDisplay?.release()
            mirrorDisplay = null
        }
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

    /**
     * Shows a live Android Auto session at [videoWidth]x[videoHeight] (the
     * resolution actually negotiated with the phone -- see
     * AaServiceDiscovery.configFromSettings()). [onSurface]/[onTouch] wire
     * the decoder and touch forwarding; the caller (CarSession) owns the
     * actual AaSessionProbe/AaVideoDecoder/AaAudioPlayer.
     */
    fun showAa(
        videoWidth: Int,
        videoHeight: Int,
        widthMargin: Int = 0,
        heightMargin: Int = 0,
        onSurface: (Surface?) -> Unit,
        onTouch: (pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit,
    ) = main.post {
        aaVideoSize = videoWidth to videoHeight
        aaMargin = widthMargin to heightMargin
        aaOnSurface = onSurface
        aaOnTouch = onTouch
        mode = Mode.AA
        showCurrent()
    }

    private var aaVideoSize: Pair<Int, Int>? = null
    private var aaMargin: Pair<Int, Int> = 0 to 0
    private var aaOnSurface: ((Surface?) -> Unit)? = null
    private var aaOnTouch: ((pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit)? = null

    private fun showCurrent() {
        val display = carDisplay?.display ?: return
        presentation?.dismiss()
        presentation = try {
            when {
                mode == Mode.MIRROR && projection != null -> mirrorPresentation(display)
                mode == Mode.AA && aaVideoSize != null -> aaPresentation(display)
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

    private fun aaPresentation(display: Display): Presentation {
        val (w, h) = aaVideoSize!!
        val (wm, hm) = aaMargin
        return AaCarPresentation(
            context, display, w, h, wm, hm,
            onExit = onExit,
            onSurface = { s -> aaOnSurface?.invoke(s) },
            onTouch = { pointers, actionIndex, action -> aaOnTouch?.invoke(pointers, actionIndex, action) },
        )
    }

    private fun mirrorPresentation(display: Display): Presentation {
        val phone = phoneSize()
        lastMirrorPortrait = phone.y >= phone.x
        // Half resolution is plenty for a 720p-tall car screen and halves encoder load.
        val mw = phone.x / 2
        val mh = phone.y / 2
        return MirrorPresentation(
            context, display, mw, mh,
            onBack = onBack,
            onExit = onExit,
            onSurface = { s -> attachMirror(s, mw, mh) },
            onPhoneStroke = { x1, y1, x2, y2, ms -> replayOnPhone(x1, y1, x2, y2, ms) },
        )
    }

    private fun attachMirror(surface: Surface?, w: Int, h: Int) {
        val p = projection ?: return
        val dpi = context.resources.displayMetrics.densityDpi / 2
        val existing = mirrorDisplay
        if (existing != null) {
            // Rotation rebuilds MirrorPresentation with the new w/h (see
            // displayListener above) -- resize the still-live VirtualDisplay to
            // match instead of just swapping its surface at the old size.
            existing.resize(w, h, dpi)
            existing.surface = surface
            return
        }
        if (surface == null) return
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
        context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
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
