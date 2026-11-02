package dev.caraalink.settings

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import dev.caraalink.video.CarHomePresentation

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, RootPreferenceFragment())
                .commit()
        }
    }

    class RootPreferenceFragment : PreferenceFragmentCompat() {

        // AA has no mid-session resolution/DPI renegotiation (confirmed --
        // see claude/roadmap.md): the only way a changed setting here takes
        // effect on an already-running AA session is a full reconnect,
        // which CarSession arms dev.caraalink.aa.AaReconnectBus to do.
        private val videoSettingKeys = setOf(
            "car_orientation", "video_resolution_mode", "video_resolution_custom_width",
            "video_resolution_custom_height", "video_frame_rate", "video_dpi_auto", "video_dpi_manual",
        )
        private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in videoSettingKeys) {
                val reconnect = dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged
                if (reconnect != null) {
                    Toast.makeText(requireContext(), "Reconnecting Android Auto with the new setting…", Toast.LENGTH_SHORT).show()
                    reconnect()
                }
            }
        }

        override fun onResume() {
            super.onResume()
            preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        }

        override fun onPause() {
            preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
            super.onPause()
        }

        private val pickBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            Thread {
                // Downscale so a 50 MP photo doesn't blow up memory on every car connect.
                val ok = runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
                    val bmp = ctx.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    } ?: error("decode failed")
                    CarHomePresentation.backgroundFile(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }.isSuccess
                activity?.runOnUiThread {
                    Toast.makeText(ctx, if (ok) "Car background set (shows on next connect)" else "Couldn't read that image", Toast.LENGTH_SHORT).show()
                    updateBackgroundSummary()
                }
            }.start()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(dev.caraalink.R.xml.root_preferences, rootKey)

            findPreference<Preference>("accessibility_service_shortcut")?.setOnPreferenceClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                true
            }

            findPreference<Preference>("car_background")?.setOnPreferenceClickListener {
                pickBackground.launch(arrayOf("image/*"))
                true
            }
            findPreference<Preference>("car_background_reset")?.setOnPreferenceClickListener {
                CarHomePresentation.backgroundFile(requireContext()).delete()
                updateBackgroundSummary()
                true
            }
            findPreference<Preference>("developer_tools")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), dev.caraalink.diagnostics.DeveloperToolsActivity::class.java))
                true
            }
            findPreference<Preference>("notification_access")?.setOnPreferenceClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                true
            }
            updateBackgroundSummary()
        }

        private fun updateBackgroundSummary() {
            val set = CarHomePresentation.backgroundFile(requireContext()).exists()
            findPreference<Preference>("car_background")?.summary =
                if (set) "Custom photo set" else "Pick a photo for the car home screen"
            findPreference<Preference>("car_background_reset")?.isVisible = set
        }
    }
}
