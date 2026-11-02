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
