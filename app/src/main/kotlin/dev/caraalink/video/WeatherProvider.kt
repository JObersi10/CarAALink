package dev.caraalink.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Current temperature + conditions for the car home screen, from Open-Meteo
 * (no API key) at the phone's last known coarse location. Returns null when
 * location permission or a fix isn't available, or the network fails.
 */
object WeatherProvider {

    fun fetch(context: Context): String? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = context.getSystemService(LocationManager::class.java)
        val loc = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        } ?: return null
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
                .format(java.util.Locale.US, loc.latitude, loc.longitude)
        )
        return runCatching {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()
            val current = JSONObject(body).getJSONObject("current")
            val temp = current.getDouble("temperature_2m").roundToInt()
            "$temp°  ${describe(current.getInt("weather_code"))}"
        }.getOrNull()
    }

    /** WMO weather interpretation codes, as documented by Open-Meteo. */
    private fun describe(code: Int) = when (code) {
        0 -> "Clear"
        1, 2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67, in 80..82 -> "Rain"
        in 71..77, 85, 86 -> "Snow"
        in 95..99 -> "Thunderstorm"
        else -> ""
    }
}
