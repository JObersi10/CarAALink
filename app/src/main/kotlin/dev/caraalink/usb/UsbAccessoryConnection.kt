package dev.caraalink.usb

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
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
        fun open(context: Context, accessory: UsbAccessory): UsbAccessoryConnection? {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val fd = usbManager.openAccessory(accessory) ?: return null
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
