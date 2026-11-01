package dev.caraalink.link

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import org.json.JSONObject

/**
 * Answers the head unit's own feature requests on the PXC main channel.
 * Reply formats CONFIRMED from CarbitLink's handlers (claude/roadmap.md):
 * - 0x10430 QUERY_GPS -> {"status", "longitude", "latitude", "speed",
 *   "altitude", "course", "time", address fields} (pp/m0.java)
 * - 0x10750 CHECK_APP_NETWORKING_CAPABILITY -> {"status": 0|1} (pp/i.java)
 */
class CarFeatures(private val context: Context) {

    @Volatile private var last: Location? = null
    private val listener = LocationListener { last = it }
    private var started = false

    fun start() {
        if (started || !hasLocation()) return
        val lm = context.getSystemService(LocationManager::class.java)
        try {
            lm.getProviders(true).forEach { p ->
                lm.getLastKnownLocation(p)?.let { if (last == null || it.time > last!!.time) last = it }
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
            }
            started = true
        } catch (_: SecurityException) {
        }
    }

    fun stop() {
        if (!started) return
        runCatching { context.getSystemService(LocationManager::class.java).removeUpdates(listener) }
        started = false
    }

    fun replyBody(cmd: Int): ByteArray? = when (cmd) {
        QUERY_GPS -> gps().toString().toByteArray(Charsets.UTF_8)
        CHECK_NETWORK -> JSONObject().put("status", if (online()) 1 else 0).toString().toByteArray(Charsets.UTF_8)
        else -> null
    }

    private fun gps(): JSONObject {
        val loc = last ?: return JSONObject().put("status", false)
        return JSONObject()
            .put("status", true)
            .put("longitude", loc.longitude)
            .put("latitude", loc.latitude)
            .put("speed", loc.speed.toDouble())
            .put("altitude", loc.altitude)
            .put("course", loc.bearing.toDouble())
            .put("time", loc.time)
            .put("province", "").put("city", "").put("district", "")
            .put("road", "").put("number", "").put("poiname", "")
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun hasLocation() =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val QUERY_GPS = 0x10430
        const val CHECK_NETWORK = 0x10750
    }
}
