package dev.caraalink.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.caraalink.MainActivity

/**
 * Automates the one step that's actually safe to automate: once Android
 * Auto's own Settings screen is in front, open its overflow menu, detect
 * whether the head unit server is currently running (menu shows "Stop...")
 * or not ("Start..."), and tap whichever is actually present -- a real
 * toggle instead of blindly tapping "Start" even when it's already on. Once
 * that tap lands, CarAALink is brought back to the front automatically. The
 * "tap Version 10x" developer-mode toggle is deliberately NOT automated
 * here.
 *
 * CONFIRMED by decompiling PhoneAuto-beta-2027-01-19.apk (jadx, this
 * session — see claude/roadmap.md): its own
 * com.google.android.projection.sink.ui.HeadUnitServerAccessibilityService
 * does exactly this and nothing more. The Version-tap stays a one-time
 * manual step guided by that app's own on-screen instructions, not something
 * their accessibility service touches. Our first implementation tried to
 * automate that step too and that's exactly why it failed on real hardware
 * (gave up at "FIND_VERSION") -- this rewrite matches the confirmed real
 * behavior instead of guessing at a fix.
 *
 * Action strings, the overflow content-description, and the arm-before-
 * launch pattern below are all taken directly from that decompiled source,
 * not invented.
 */
class HeadUnitServerAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HeadUnitServerA11y"
        private const val ARM_WINDOW_MS = 20_000L

        private val START_TEXTS = arrayOf("Start head unit server", "Start headunit server")
        private val STOP_TEXTS = arrayOf("Stop head unit server", "Stop headunit server")
        private val OVERFLOW_DESCRIPTIONS = arrayOf("More options", "More")

        @Volatile
        var instance: HeadUnitServerAccessibilityService? = null

        @Volatile
        private var armed = false

        @Volatile
        private var armedUntil = 0L

        /** Call right before launching Android Auto's Settings UI. */
        fun arm() {
            armed = true
            armedUntil = SystemClock.uptimeMillis() + ARM_WINDOW_MS
        }

        fun disarm() {
            armed = false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overflowOpened = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!armed) return
        if (SystemClock.uptimeMillis() >= armedUntil) {
            armed = false
            overflowOpened = false
            return
        }

        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            if (overflowOpened) {
                // Detect current state from whichever label the menu is
                // actually showing, then tap that one -- this is the toggle.
                val startNode = findExactText(root, START_TEXTS)
                val stopNode = findExactText(root, STOP_TEXTS)
                val target = startNode ?: stopNode
                if (target != null) {
                    val label = if (startNode != null) "Start" else "Stop"
                    if (clickUpToAncestor(target)) {
                        Log.i(TAG, "Head unit server was ${if (startNode != null) "off" else "on"} -- tapped '$label head unit server'")
                        deactivate(pressBack = false)
                        returnToCarAALink()
                        return
                    }
                }
            }

            if (!overflowOpened && clickByDescription(root, OVERFLOW_DESCRIPTIONS)) {
                Log.i(TAG, "Opened overflow menu")
                overflowOpened = true
            }
        } finally {
            root.recycle()
        }
    }

    private fun deactivate(pressBack: Boolean) {
        armed = false
        overflowOpened = false
        if (pressBack) {
            handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 600L)
        }
    }

    /** Hands focus back to CarAALink once the toggle tap has landed. */
    private fun returnToCarAALink() {
        handler.postDelayed({
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            }
        }, 700L)
    }

    private fun findExactText(root: AccessibilityNodeInfo, candidates: Array<String>): AccessibilityNodeInfo? {
        for (candidate in candidates) {
            val matches = root.findAccessibilityNodeInfosByText(candidate) ?: continue
            for (node in matches) {
                if (node.text?.toString()?.equals(candidate, ignoreCase = true) == true) return node
            }
        }
        return null
    }

    private fun clickExactText(root: AccessibilityNodeInfo, candidates: Array<String>): Boolean {
        val node = findExactText(root, candidates) ?: return false
        return clickUpToAncestor(node)
    }

    private fun clickByDescription(node: AccessibilityNodeInfo, candidates: Array<String>): Boolean {
        val desc = node.contentDescription?.toString()
        if (desc != null && candidates.any { it.equals(desc, ignoreCase = true) } && clickUpToAncestor(node)) {
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickByDescription(child, candidates)) return true
        }
        return false
    }

    private fun clickUpToAncestor(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var hops = 0
        while (target != null && hops < 6) {
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            target = target.parent
            hops++
        }
        return false
    }
}
