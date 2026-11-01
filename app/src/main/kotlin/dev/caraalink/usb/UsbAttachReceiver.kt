package dev.caraalink.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionStateHolder

/**
 * Fires when the phone is plugged into the car's USB port.
 *
 * CONFIRMED by RE pass 1 (claude/roadmap.md): the phone is the USB
 * *accessory*, the head unit is the *host* — so USB_ACCESSORY_ATTACHED is
 * the only action that actually arrives here on real hardware.
 *
 * This only records that the accessory is attached — it does NOT auto-start
 * the sniffer or the real protocol. The sniffer is a manual dev-tools action
 * (see claude/roadmap.md); real protocol auto-start lands in Phase 3.
 */
class UsbAttachReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED) return

        @Suppress("DEPRECATION")
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            ?: return

        ConnectionStateHolder.update {
            it.copy(
                phase = ConnectionPhase.ATTACHED,
                accessoryManufacturer = accessory.manufacturer,
                accessoryModel = accessory.model,
            )
        }
    }
}
