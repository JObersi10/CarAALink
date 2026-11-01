package dev.caraalink.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Automates Android Auto's own hidden developer toggle so the user doesn't
 * have to do it by hand every time: Settings -> tap "Version" 10x -> overflow
 * menu -> "Start head unit server". These are AA's own publicly known steps
 * (the same ones described in the project brief), not anything extracted from
 * a third-party app's source — see claude/claude.md's no-fabrication rule and
 * claude/roadmap.md item 8 (PhoneAuto's HeadUnitServerAccessibilityService
 * confirms this is gated behind a first-party AA feature, not custom
 * automation on Google's side; this class is our own automation of it).
 *
 * This is heuristic UI automation driven by visible text, not a stable API:
 * AA can change its Settings layout or copy between versions, which would
 * require updating the text/description matches below.
 */
class HeadUnitServerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HeadUnitServerA11y"
        private const val VERSION_TAPS_NEEDED = 10
        private const val POLL_DELAY_MS = 350L
        private const val TAP_DELAY_MS = 150L
        private const val MAX_POLL_ATTEMPTS = 40

        @Volatile
        var instance: HeadUnitServerAccessibilityService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    private enum class Stage { IDLE, FIND_VERSION, TAP_VERSION, FIND_OVERFLOW, FIND_START_ITEM, DONE }

    private var stage = Stage.IDLE
    private var versionTapsRemaining = 0
    private var attemptsLeft = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        stage = Stage.IDLE
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally unused: the poll loop below drives this rather than
        // individual window events, which AA's settings screens don't fire
        // reliably for every state change we care about.
    }

    override fun onInterrupt() {}

    /** Call once Android Auto's own app is already in the foreground. */
    fun requestHeadUnitServerStart() {
        stage = Stage.FIND_VERSION
        versionTapsRemaining = VERSION_TAPS_NEEDED
        attemptsLeft = MAX_POLL_ATTEMPTS
        handler.postDelayed(::poll, POLL_DELAY_MS)
    }

    private fun poll() {
        if (stage == Stage.IDLE || stage == Stage.DONE) return
        if (attemptsLeft-- <= 0) {
            Log.w(TAG, "Gave up at stage $stage — AA's settings UI didn't match the expected text")
            stage = Stage.IDLE
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            handler.postDelayed(::poll, POLL_DELAY_MS)
            return
        }

        when (stage) {
            Stage.FIND_VERSION -> {
                val versionNode = findByText(root, "Version")
                if (versionNode != null) {
                    clickNode(versionNode)
                    versionTapsRemaining--
                    stage = Stage.TAP_VERSION
                    handler.postDelayed(::poll, TAP_DELAY_MS)
                } else {
                    scrollDown(root)
                    handler.postDelayed(::poll, POLL_DELAY_MS)
                }
            }

            Stage.TAP_VERSION -> {
                val versionNode = findByText(root, "Version")
                if (versionNode != null && versionTapsRemaining > 0) {
                    clickNode(versionNode)
                    versionTapsRemaining--
                    handler.postDelayed(::poll, TAP_DELAY_MS)
                } else {
                    stage = Stage.FIND_OVERFLOW
                    attemptsLeft = MAX_POLL_ATTEMPTS
                    handler.postDelayed(::poll, 500L)
                }
            }

            Stage.FIND_OVERFLOW -> {
                val overflow = findByDescription(root, "More options")
                if (overflow != null) {
                    clickNode(overflow)
                    stage = Stage.FIND_START_ITEM
                    attemptsLeft = MAX_POLL_ATTEMPTS
                    handler.postDelayed(::poll, 400L)
                } else {
                    handler.postDelayed(::poll, POLL_DELAY_MS)
                }
            }

            Stage.FIND_START_ITEM -> {
                val startItem = findByText(root, "Start head unit server")
                if (startItem != null) {
                    clickNode(startItem)
                    stage = Stage.DONE
                    Log.i(TAG, "Tapped 'Start head unit server'")
                } else {
                    handler.postDelayed(::poll, POLL_DELAY_MS)
                }
            }

            else -> {}
        }
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(text).firstOrNull()

    private fun findByDescription(root: AccessibilityNodeInfo, description: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString()?.contains(description, ignoreCase = true) == true) {
                return node
            }
            for (i in 0 until node.childCount) {
                search(node.getChild(i))?.let { return it }
            }
            return null
        }
        return search(root)
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        (target ?: node).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun scrollDown(root: AccessibilityNodeInfo) {
        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                search(node.getChild(i))?.let { return it }
            }
            return null
        }
        search(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }
}
