package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.caraalink.service.UsbLinkForegroundService

/**
 * Dev menu: start/stop the USB sniffer diagnostic and export whatever it's
 * captured so far. Everything here is for feeding evidence back into the
 * protocol RE work (claude/roadmap.md Phase 1) — none of it is part of the
 * real app flow.
 */
class DeveloperToolsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(this).apply {
            text = "USB sniffer logs raw bytes from the head unit to a file — " +
                "no protocol logic, just capture. Needs a USB accessory already attached."
        })

        root.addView(Button(this).apply {
            text = "Start USB sniffer"
            setOnClickListener { startSniffer() }
        })

        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startService(Intent(this@DeveloperToolsActivity, UsbLinkForegroundService::class.java)
                    .setAction(UsbLinkForegroundService.ACTION_STOP))
            }
        })

        root.addView(Button(this).apply {
            text = "Export latest log"
            setOnClickListener { exportLatestLog() }
        })

        setContentView(root)
    }

    private fun startSniffer() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val accessory = usbManager.accessoryList?.firstOrNull()
        if (accessory == null) {
            Toast.makeText(this, "No USB accessory currently attached", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_START_SNIFFER)
            .putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
        startForegroundService(intent)
    }

    private fun exportLatestLog() {
        val logger = DiagnosticLogger(this, "usb-sniff")
        val latest = logger.allLogFiles().firstOrNull()
        if (latest == null) {
            Toast.makeText(this, "No sniffer logs yet", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent.createChooser(logger.shareFileIntent(latest), "Share CarAALink USB log"))
    }
}
