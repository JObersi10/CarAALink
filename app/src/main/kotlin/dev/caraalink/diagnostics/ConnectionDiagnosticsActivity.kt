package dev.caraalink.diagnostics

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.ConnectionStateHolder

/**
 * Live view of [ConnectionStateHolder] — phase, accessory identity, byte
 * counters, last error, reconnect attempts. Meant to be glanced at while
 * actually plugged into the car, instead of pulling adb logcat.
 */
class ConnectionDiagnosticsActivity : Activity() {

    private lateinit var statusView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener: (ConnectionSnapshot) -> Unit = { snapshot ->
        mainHandler.post { render(snapshot) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusView = TextView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusView)
        })
        render(ConnectionStateHolder.snapshot)
    }

    override fun onResume() {
        super.onResume()
        ConnectionStateHolder.addListener(listener)
        render(ConnectionStateHolder.snapshot)
    }

    override fun onPause() {
        ConnectionStateHolder.removeListener(listener)
        super.onPause()
    }

    private fun render(snapshot: ConnectionSnapshot) {
        statusView.text = buildString {
            appendLine("Phase: ${snapshot.phase}")
            appendLine("Accessory: ${snapshot.accessoryManufacturer ?: "-"} / ${snapshot.accessoryModel ?: "-"}")
            appendLine("Bytes read: ${snapshot.bytesRead}")
            appendLine("Bytes written: ${snapshot.bytesWritten}")
            appendLine("Reconnect attempts: ${snapshot.reconnectAttempts}")
            appendLine("Last error: ${snapshot.lastError ?: "-"}")
        }
    }
}
