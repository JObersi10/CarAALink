package dev.headunitbridge.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager

/**
 * Fires when the phone is plugged into the car's USB port.
 *
 * Which action actually arrives (USB_DEVICE_ATTACHED vs USB_ACCESSORY_ATTACHED)
 * depends on which side of the USB link the head unit expects us to play —
 * that role is NOT yet confirmed. See claude/roadmap.md "USB role" item and
 * docs/protocol/usb-role.md once populated. Do not assume a role here; this
 * stub only logs what Android itself delivers.
 */
class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                // TODO: phone acting as USB host — head unit is the attached device.
            }
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                // TODO: phone acting as USB accessory — head unit is the host.
            }
        }
    }
}
