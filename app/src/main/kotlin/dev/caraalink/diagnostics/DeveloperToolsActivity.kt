package dev.caraalink.diagnostics

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
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

    companion object {
        private const val TAG = "DeveloperToolsActivity"
    }

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

        root.addView(TextView(this).apply {
            text = "ADB handshake probe: completes the CNXN handshake the head unit " +
                "opens with (confirmed real ADB, see claude/roadmap.md), then logs " +
                "whatever it asks for next. Doesn't act on anything it receives."
        })

        root.addView(Button(this).apply {
            text = "Start ADB handshake probe"
            setOnClickListener { startAdbProbe() }
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

    private fun exportLatestLog() {
        val allFiles = DiagnosticLogger(this, "app").allLogFiles() // shared logs/ dir, all tags
        val latestApp = allFiles.firstOrNull { it.name.startsWith("app-") }
        val latestSniff = allFiles.firstOrNull { it.name.startsWith("usb-sniff-") }
        val latestProbe = allFiles.firstOrNull { it.name.startsWith("adb-probe-") }
        val toShare = listOfNotNull(latestApp, latestSniff, latestProbe)
        if (toShare.isEmpty()) {
            Toast.makeText(this, "No logs yet", Toast.LENGTH_LONG).show()
            return
        }
        Log.i(TAG, "exportLatestLog: sharing ${toShare.map { it.name }}")
        startActivity(
            Intent.createChooser(
                DiagnosticLogger.shareFilesIntent(this, toShare),
                "Share CarAALink logs",
            )
        )
    }
}
