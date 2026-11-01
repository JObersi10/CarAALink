package dev.caraalink.service

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Invisible activity that asks for screen-capture permission (needed for the
 * car's "Phone screen" tile) and hands the result to [UsbLinkForegroundService].
 */
class ProjectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Deprecated("Activity result API not used in this plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, UsbLinkForegroundService::class.java)
                    .setAction(UsbLinkForegroundService.ACTION_PROJECTION_GRANTED)
                    .putExtra(UsbLinkForegroundService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(UsbLinkForegroundService.EXTRA_RESULT_DATA, data)
            )
        }
        finish()
    }

    companion object {
        private const val REQUEST_CODE = 41
    }
}
