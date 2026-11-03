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
            "aa_auto_crop", "video_downscale_720p",
        )
        private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in videoSettingKeys) {
                updateDpiSummary()
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
            updateDpiSummary()

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
            findPreference<Preference>("car_profiles")?.setOnPreferenceClickListener {
                showCarProfilesDialog()
                true
            }
            findPreference<Preference>("check_for_updates_now")?.setOnPreferenceClickListener {
                dev.caraalink.update.UpdateChecker.checkNow(requireContext(), manual = true)
                true
            }
            updateBackgroundSummary()
        }

        private fun showCarProfilesDialog() {
            val context = requireContext()
            val profiles = CarProfiles.listProfiles(context)
            val items = (profiles + "+ Save current settings as...").toTypedArray()
            android.app.AlertDialog.Builder(context)
                .setTitle("Car profiles")
                .setItems(items) { _, index ->
                    if (index == profiles.size) {
                        promptSaveProfile()
                    } else {
                        showProfileActionsDialog(profiles[index])
                    }
                }
                .setNegativeButton("Close", null)
                .show()
        }

        private fun promptSaveProfile() {
            val context = requireContext()
            val input = android.widget.EditText(context).apply { hint = "e.g. My Car" }
            android.app.AlertDialog.Builder(context)
                .setTitle("Save current settings as")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        CarProfiles.saveProfile(context, name)
                        Toast.makeText(context, "Saved \"$name\"", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun showProfileActionsDialog(name: String) {
            val context = requireContext()
            android.app.AlertDialog.Builder(context)
                .setTitle(name)
                .setItems(arrayOf("Load", "Delete")) { _, which ->
                    if (which == 0) {
                        CarProfiles.loadProfile(context, name)
                        Toast.makeText(context, "Loaded \"$name\" -- reconnecting if AA is active", Toast.LENGTH_SHORT).show()
                    } else {
                        CarProfiles.deleteProfile(context, name)
                        Toast.makeText(context, "Deleted \"$name\"", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        private fun updateBackgroundSummary() {
            val set = CarHomePresentation.backgroundFile(requireContext()).exists()
            findPreference<Preference>("car_background")?.summary =
                if (set) "Custom photo set" else "Pick a photo for the car home screen"
            findPreference<Preference>("car_background_reset")?.isVisible = set
        }

        /**
         * Shows the actual computed density value so the owner can see what's
         * really being sent while calibrating manually -- empirically (not
         * the simple dp=px*density/160 model originally assumed), a HIGHER
         * declared density makes AA's UI look SMALLER on a real car screen,
         * confirmed from live testing. No confirmed correct target value
         * yet, so this doesn't change the formula, just makes the current
         * number visible instead of hidden.
         */
        private fun updateDpiSummary() {
            val context = requireContext()
            val config = dev.caraalink.aa.AaServiceDiscovery.configFromSettings(context)
            findPreference<androidx.preference.SwitchPreferenceCompat>("video_dpi_auto")?.summary =
                "Scale density to the chosen resolution (currently sending density=${config.density})"
            findPreference<Preference>("video_dpi_manual")?.summary =
                "Only used when auto-detect is off (currently sending density=${config.density})"
        }
    }
}
