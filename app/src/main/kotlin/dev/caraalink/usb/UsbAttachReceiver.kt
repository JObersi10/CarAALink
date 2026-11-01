package dev.caraalink.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import dev.caraalink.settings.AppSettings

/**
 * Fires when the phone is plugged into the car's USB port.
 *
 * CONFIRMED by RE pass 1 (claude/roadmap.md): the phone is the USB
 * *accessory*, the head unit is the *host* — so USB_ACCESSORY_ATTACHED is
 * the only action that actually arrives here on real hardware. [AppSettings.usbRoleOverride]
 * exists for debug/testing only; this receiver does not act on it yet.
 */
class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return

        @Suppress("DEPRECATION")
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            ?: return

        // TODO: open the accessory (UsbManager.openAccessory) and hand the fd
        // to the protocol module once CarbitLinkProtocol implements the
        // confirmed frame format — see claude/roadmap.md Phase 3.
        if (AppSettings.autoStartHeadUnitServer(context)) {
            // TODO: trigger HeadUnitServerLauncher.start(context) once we're
            // ready to actually run the pipeline end to end.
        }
    }
}
