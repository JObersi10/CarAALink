package dev.caraalink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.caraalink.MainActivity
import dev.caraalink.connection.ConnectionPhase
import dev.caraalink.connection.ConnectionSnapshot
import dev.caraalink.connection.ConnectionStateHolder
import dev.caraalink.diagnostics.UsbSnifferDiagnostic
import dev.caraalink.settings.AppSettings

/**
 * Keeps the USB link alive while the app is backgrounded (screen off is
 * expected and fine — see claude/roadmap.md; this does NOT force the screen
 * on, only holds a partial wake lock so the CPU doesn't doze mid-read).
 * Currently only drives [UsbSnifferDiagnostic]; swap in the real protocol
 * once Phase 3 lands.
 */
class UsbLinkForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var sniffer: UsbSnifferDiagnostic? = null
    private var reconnectRunnable: Runnable? = null
    private var pendingAccessory: UsbAccessory? = null

    private val stateListener: (ConnectionSnapshot) -> Unit = { updateNotification(it) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ConnectionStateHolder.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_SNIFFER -> {
                val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
                if (accessory != null) {
                    Log.i(TAG, "onStartCommand: ACTION_START_SNIFFER with accessory=${accessory.manufacturer}/${accessory.model}")
                    pendingAccessory = accessory
                    // CRASH SEEN ON REAL HARDWARE (claude/roadmap.md): starting a
                    // connectedDevice-typed foreground service throws
                    // SecurityException once the accessory's permission grant has
                    // lapsed (e.g. it was unplugged between the intent being sent
                    // and this running). Don't let that take the whole app down.
                    val started = try {
                        startForeground(NOTIFICATION_ID, buildNotification(ConnectionStateHolder.snapshot))
                        true
                    } catch (e: SecurityException) {
                        Log.w(TAG, "startForeground(connectedDevice) refused -- accessory permission likely lapsed", e)
                        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "No USB accessory permission") }
                        stopSelf()
                        false
                    }
                    if (started) {
                        acquireWakeLock()
                        beginSniffing(accessory)
                    }
                } else {
                    Log.w(TAG, "onStartCommand: ACTION_START_SNIFFER with no EXTRA_ACCESSORY")
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand: ACTION_STOP")
                stopSelfCleanly()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginSniffing(accessory: UsbAccessory) {
        Log.i(TAG, "beginSniffing: ${accessory.manufacturer}/${accessory.model}")
        sniffer?.stop()
        val s = UsbSnifferDiagnostic(this)
        sniffer = s
        s.start(accessory)
    }

    override fun onDestroy() {
        ConnectionStateHolder.removeListener(stateListener)
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        sniffer?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Called by the sniffer/transport layer (via ConnectionStateHolder) on link loss. */
    private fun maybeScheduleReconnect(snapshot: ConnectionSnapshot) {
        if (snapshot.phase != ConnectionPhase.ERROR) return
        if (!AppSettings.autoReconnectUsb(this)) return
        val accessory = pendingAccessory ?: return

        if (snapshot.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "maybeScheduleReconnect: giving up after $MAX_RECONNECT_ATTEMPTS attempts")
            stopSelfCleanly()
            return
        }

        val attempt = snapshot.reconnectAttempts + 1
        val delayMs = (1000L * (1 shl minOf(attempt, 5))).coerceAtMost(30_000L) // 2s..30s backoff
        ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.RECONNECTING, reconnectAttempts = attempt) }

        val runnable = Runnable {
            // CRASH SEEN ON REAL HARDWARE (claude/roadmap.md): retrying with a
            // stale UsbAccessory reference after it's actually been unplugged
            // throws from openAccessory() instead of failing gracefully.
            // UsbAccessoryConnection.open() now catches that, but checking
            // the accessory is still actually present first avoids the retry
            // (and the crash-triggering startForeground/openAccessory calls)
            // entirely when there's nothing left to reconnect to.
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val stillPresent = usbManager.accessoryList?.any {
                it.manufacturer == accessory.manufacturer && it.model == accessory.model
            } == true
            if (stillPresent) {
                beginSniffing(accessory)
            } else {
                Log.w(TAG, "maybeScheduleReconnect: accessory no longer present, not retrying")
                pendingAccessory = null
                stopSelfCleanly()
            }
        }
        reconnectRunnable = runnable
        handler.postDelayed(runnable, delayMs)
    }

    private fun stopSelfCleanly() {
        sniffer?.stop()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "caraalink:usb-link").apply {
            setReferenceCounted(false)
            acquire(/* no timeout — released explicitly on stop/destroy */)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "USB link", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification(snapshot: ConnectionSnapshot) {
        maybeScheduleReconnect(snapshot)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
    }

    private fun buildNotification(snapshot: ConnectionSnapshot): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, UsbLinkForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = when (snapshot.phase) {
            ConnectionPhase.DISCONNECTED -> "Disconnected"
            ConnectionPhase.ATTACHED -> "USB attached"
            ConnectionPhase.SNIFFING -> "Diagnostic sniffer running (${snapshot.bytesRead} bytes)"
            ConnectionPhase.STREAMING -> "Streaming"
            ConnectionPhase.RECONNECTING -> "Reconnecting (attempt ${snapshot.reconnectAttempts})…"
            ConnectionPhase.ERROR -> "Error: ${snapshot.lastError ?: "unknown"}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CarAALink")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "UsbLinkForegroundService"
        private const val CHANNEL_ID = "usb_link"
        private const val NOTIFICATION_ID = 1
        private const val MAX_RECONNECT_ATTEMPTS = 5

        const val ACTION_START_SNIFFER = "dev.caraalink.service.action.START_SNIFFER"
        const val ACTION_STOP = "dev.caraalink.service.action.STOP"
    }
}
