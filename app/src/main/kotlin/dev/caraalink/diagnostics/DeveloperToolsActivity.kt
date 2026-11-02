package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import dev.caraalink.aa.AaIdentity
import dev.caraalink.aa.AaTlsReceiver
import dev.caraalink.aa.AaVersionProbe
import dev.caraalink.service.UsbLinkForegroundService

/**
 * Dev menu: start/stop the USB sniffer diagnostic and export whatever it's
 * captured so far. Everything here is for feeding evidence back into the
 * protocol RE work (claude/roadmap.md Phase 1) — none of it is part of the
 * real app flow.
 */
class DeveloperToolsActivity : Activity() {

    companion object {
        private const val TAG = "DeveloperToolsActivity"
    }

    private var aaReceiver: AaTlsReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        root.addView(section("USB sniffer logs raw bytes from the head unit to a file — " +
            "no protocol logic, just capture. Needs a USB accessory already attached."))

        root.addView(button("Start USB sniffer") { startSniffer() })

        root.addView(section("ADB handshake probe: completes the CNXN handshake the head unit " +
            "opens with (confirmed real ADB, see claude/roadmap.md), then logs " +
            "whatever it asks for next. Doesn't act on anything it receives."))

        root.addView(button("Start ADB handshake probe") { startAdbProbe() })

        root.addView(section("AA TLS receiver: presents the head-unit identity in assets/aa — " +
            "the JVC Kenwood leaf issued by Google Automotive Link (see " +
            "assets/aa/PROVENANCE.md) — and dials the phone's own AA gearhead on " +
            "127.0.0.1:5277, trying the TLS role both ways until one handshakes. " +
            "Logs the handshake and whatever bytes follow; sends nothing above TLS."))

        root.addView(button("Start AA TLS receiver") { startAaReceiver() })
        root.addView(button("Stop AA TLS receiver") { stopAaReceiver() })

        root.addView(section("AA version probe (rung 1): sends the plaintext AA VERSION_REQUEST " +
            "to the phone's gearhead on 127.0.0.1:5277 and decodes whatever frames " +
            "come back. No TLS, no channel opens — this is the step before any of that."))

        root.addView(button("Start AA version probe") { startAaVersionProbe() })
        root.addView(button("Stop") {
            startService(Intent(this@DeveloperToolsActivity, UsbLinkForegroundService::class.java)
                .setAction(UsbLinkForegroundService.ACTION_STOP))
        })
        root.addView(button("Export latest log") { exportLatestLog() })

        root.addView(section("AA on-phone preview: starts (or attaches to) the AA session and " +
            "renders its video on this phone's screen, with touch forwarded to the " +
            "input channel -- for testing without the car."))

        root.addView(button("Preview AA session (interactive)") {
            startActivity(Intent(this@DeveloperToolsActivity, dev.caraalink.aa.AaPreviewActivity::class.java))
        })

        setContentView(
            ScrollView(this).apply {
                addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            },
        )
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#9AA7B2"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun button(label: String, onClick: () -> Unit) = MaterialButton(
        this,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply {
            topMargin = dp(6)
        }
        cornerRadius = dp(16)
        setTextColor(Color.parseColor("#F2F5F7"))
        strokeColor = ColorStateList.valueOf(Color.parseColor("#2C3640"))
        strokeWidth = dp(1)
        rippleColor = ColorStateList.valueOf(Color.parseColor("#4036C9C6"))
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun startSniffer() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val list = usbManager.accessoryList
        Log.i(TAG, "startSniffer: accessoryList size=${list?.size ?: 0}")
        val accessory = list?.firstOrNull()
        if (accessory == null) {
            Log.w(TAG, "startSniffer: no accessory in UsbManager.accessoryList -- nothing to sniff")
            Toast.makeText(this, "No USB accessory currently attached", Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "startSniffer: found accessory manufacturer=${accessory.manufacturer} model=${accessory.model} version=${accessory.version}")
        val intent = Intent(this, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_SNIFFER)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        startForegroundService(intent)
        Log.i(TAG, "startSniffer: sent ACTION_START_SNIFFER to UsbLinkForegroundService")
    }

    private fun startAdbProbe() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val list = usbManager.accessoryList
        Log.i(TAG, "startAdbProbe: accessoryList size=${list?.size ?: 0}")
        val accessory = list?.firstOrNull()
        if (accessory == null) {
            Log.w(TAG, "startAdbProbe: no accessory in UsbManager.accessoryList")
            Toast.makeText(this, "No USB accessory currently attached", Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "startAdbProbe: found accessory manufacturer=${accessory.manufacturer} model=${accessory.model}")
        val intent = Intent(this, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_ADB_PROBE)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        startForegroundService(intent)
        Log.i(TAG, "startAdbProbe: sent ACTION_START_ADB_PROBE to UsbLinkForegroundService")
    }

    private fun startAaReceiver() {
        if (!AaIdentity.isAvailable(this)) {
            Log.w(TAG, "startAaReceiver: no AA identity loadable")
            Toast.makeText(this, "AA identity missing — see assets/aa/PROVENANCE.md", Toast.LENGTH_LONG).show()
            return
        }
        if (AaTlsReceiver.active != null) {
            Toast.makeText(this, "AA TLS receiver already running", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(TAG, "startAaReceiver: starting receiver")
        val receiver = AaTlsReceiver(this)
        aaReceiver = receiver
        receiver.start()
        Toast.makeText(this, "AA TLS receiver started — check the aa-tls log", Toast.LENGTH_SHORT).show()
    }

    private fun startAaVersionProbe() {
        if (AaVersionProbe.active != null) {
            Toast.makeText(this, "Version probe already running", Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(TAG, "startAaVersionProbe")
        AaVersionProbe(this).start()
        Toast.makeText(this, "AA version probe started — check the aa-version log", Toast.LENGTH_SHORT).show()
    }

    private fun stopAaReceiver() {
        Log.i(TAG, "stopAaReceiver")
        (aaReceiver ?: AaTlsReceiver.active)?.stop()
        aaReceiver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAaReceiver()
    }

    private fun exportLatestLog() {
        // One file, everything already interleaved chronologically -- no more
        // sending five attachments and reading them side by side by hand.
        val logger = DiagnosticLogger(this, "app")
        val merged = runCatching { logger.mergedLogFile() }.getOrElse { e ->
            Log.e(TAG, "exportLatestLog: merge failed", e)
            null
        }
        if (merged == null) {
            Toast.makeText(this, "No logs yet", Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "exportLatestLog: sharing merged ${merged.name} (${merged.length()} bytes)")
        Toast.makeText(this, "Sharing ${merged.name}", Toast.LENGTH_SHORT).show()
        startActivity(
            Intent.createChooser(
                logger.shareFileIntent(merged),
                "Share CarAALink logs",
            )
        )
    }
}
