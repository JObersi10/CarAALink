package dev.caraalink.usb

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Thin wrapper over [UsbManager.openAccessory] — opens the fd and exposes it
 * as raw streams. CONFIRMED by RE pass 1 (claude/roadmap.md): the phone is
 * the accessory here, matching how the real CarbitLink app's
 * EPSDK.openAccessory() uses this exact API.
 */
class UsbAccessoryConnection private constructor(
    private val usbManager: UsbManager,
    val accessory: UsbAccessory,
    val input: FileInputStream,
    val output: FileOutputStream,
) : AutoCloseable {

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        private const val TAG = "UsbAccessoryConnection"

        /**
         * Crash seen on real hardware (claude/roadmap.md): UsbManager.openAccessory()
         * THROWS IllegalArgumentException("no accessory attached") -- it does not
         * just return null -- when the accessory has been physically detached
         * since the caller last saw it (e.g. a stale reference reused by a
         * reconnect retry). Catch broadly here so a vanished accessory is a
         * null return, not an uncaught crash.
         */
        fun open(context: Context, accessory: UsbAccessory): UsbAccessoryConnection? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val fd = try {
                usbManager.openAccessory(accessory)
            } catch (e: Exception) {
                Log.w(TAG, "openAccessory threw -- treating as not attached", e)
                null
            } ?: return null
            val descriptor = fd.fileDescriptor
            return UsbAccessoryConnection(
                usbManager,
                accessory,
                FileInputStream(descriptor),
                FileOutputStream(descriptor),
            )
        }
    }
}
