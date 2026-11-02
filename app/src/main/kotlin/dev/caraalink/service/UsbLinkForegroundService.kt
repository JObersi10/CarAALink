package dev.caraalink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import dev.caraalink.accessibility.HeadUnitServerAccessibilityService
import dev.caraalink.link.CarSession
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
import dev.caraalink.diagnostics.AdbHandshakeProbe
import dev.caraalink.diagnostics.UsbSnifferDiagnostic

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
    private var adbProbe: AdbHandshakeProbe? = null
    private var session: CarSession? = null
    private var pendingProjection: Pair<Int, Intent>? = null
    private var pendingAccessory: UsbAccessory? = null
    private var resumeActiveMode: ((UsbAccessory) -> Unit)? = null
    private var detachStopRunnable: Runnable? = null

    private val stateListener: (ConnectionSnapshot) -> Unit = { updateNotification(it) }

    // Shouldn't keep running (and showing a notification) indefinitely once
    // actually unplugged from the car -- 30s grace in case it's a brief
    // reseat, then quietly stop: no lingering service, no lingering AA
    // reconnect loop, notification gone.
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "USB_ACCESSORY_DETACHED -- stopping in 30s unless reattached")
            detachStopRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                Log.i(TAG, "detachStopRunnable: still unplugged after 30s, stopping")
                stopSelfCleanly()
            }
            detachStopRunnable = runnable
            handler.postDelayed(runnable, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ConnectionStateHolder.addListener(stateListener)
        val filter = IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(detachReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")
        when (intent?.action) {
            ACTION_START_SNIFFER -> handleStart(intent) { accessory -> beginSniffing(accessory) }
            ACTION_START_ADB_PROBE -> handleStart(intent) { accessory -> beginAdbProbe(accessory) }
            ACTION_START_SESSION -> handleStart(intent) { accessory -> beginSession(accessory) }
            ACTION_PROJECTION_GRANTED -> onProjectionGranted(intent)
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand: ACTION_STOP")
                stopSelfCleanly()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent, begin: (UsbAccessory) -> Unit) {
        val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
        if (accessory == null) {
            Log.w(TAG, "handleStart: ${intent.action} with no EXTRA_ACCESSORY")
            return
        }
        Log.i(TAG, "handleStart: ${intent.action} with accessory=${accessory.manufacturer}/${accessory.model}")
        pendingAccessory = accessory
        detachStopRunnable?.let { handler.removeCallbacks(it) }
        detachStopRunnable = null
        // CRASH SEEN ON REAL HARDWARE (claude/roadmap.md): starting a
        // connectedDevice-typed foreground service throws SecurityException
        // once the accessory's permission grant has lapsed (e.g. it was
        // unplugged between the intent being sent and this running). Don't
        // let that take the whole app down.
        val started = try {
            // Explicit type: the manifest also lists mediaProjection, which needs consent first.
            startForeground(NOTIFICATION_ID, buildNotification(ConnectionStateHolder.snapshot),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground(connectedDevice) refused -- accessory permission likely lapsed", e)
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "No USB accessory permission") }
            stopSelf()
            false
        }
        if (started) {
            acquireWakeLock()
            begin(accessory)
        }
    }

    private fun beginSniffing(accessory: UsbAccessory) {
        Log.i(TAG, "beginSniffing: ${accessory.manufacturer}/${accessory.model}")
        adbProbe?.stop()
        sniffer?.stop()
        val s = UsbSnifferDiagnostic(this)
        sniffer = s
        resumeActiveMode = ::beginSniffing
        s.start(accessory)
    }

    private fun beginAdbProbe(accessory: UsbAccessory) {
        Log.i(TAG, "beginAdbProbe: ${accessory.manufacturer}/${accessory.model}")
        sniffer?.stop()
        adbProbe?.stop()
        val p = AdbHandshakeProbe(this)
        adbProbe = p
        resumeActiveMode = ::beginAdbProbe
        p.start(accessory)
    }

    private fun beginSession(accessory: UsbAccessory) {
        Log.i(TAG, "beginSession: ${accessory.manufacturer}/${accessory.model}")
        sniffer?.stop()
        adbProbe?.stop()
        session?.stop()
        val s = CarSession(this, accessory, phoneActions) { reason ->
            handler.post {
                Log.i(TAG, "session ended: $reason")
                session = null
                stopSelfCleanly()
            }
        }
        session = s
        if (!s.start()) {
            session = null
            ConnectionStateHolder.update { it.copy(phase = ConnectionPhase.ERROR, lastError = "openAccessory() returned null") }
            stopSelfCleanly()
            return
        }
        pendingProjection?.let { (code, data) ->
            pendingProjection = null
            applyProjection(code, data)
        }
    }

    private fun onProjectionGranted(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return
        if (session == null) {
            // Consent can arrive before the session's foreground start; hold it until then.
            pendingProjection = code to data
            if (pendingAccessory == null) {
                // Started only for this; nothing to attach to yet.
                runCatching {
                    startForeground(NOTIFICATION_ID, buildNotification(ConnectionStateHolder.snapshot),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                }
                stopSelfCleanly()
            }
            return
        }
        applyProjection(code, data)
    }

    private fun applyProjection(code: Int, data: Intent) {
        try {
            // Android 14+: the foreground service must carry the mediaProjection type before getMediaProjection().
            startForeground(
                NOTIFICATION_ID,
                buildNotification(ConnectionStateHolder.snapshot),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            if (projection != null) session?.setProjection(projection)
        } catch (e: Exception) {
            Log.w(TAG, "applyProjection failed", e)
        }
    }

    private val phoneActions = object : CarSession.PhoneActions {
        override fun back() {
            HeadUnitServerAccessibilityService.instance?.backOnPhone()
                ?: Log.w(TAG, "back ignored: accessibility service off")
        }

        override fun requestScreenCapture() {
            val ctx = this@UsbLinkForegroundService
            val intent = Intent(ctx, ProjectionRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(intent) }.onFailure { Log.w(TAG, "requestScreenCapture failed", it) }
            // Android may silently block activity starts from the background; the notification always works.
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID_CAPTURE, NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle("Show phone screen on the car")
                .setContentText("Tap to allow screen mirroring")
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(PendingIntent.getActivity(ctx, 1, intent, PendingIntent.FLAG_IMMUTABLE))
                .build())
        }
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        ConnectionStateHolder.removeListener(stateListener)
        detachStopRunnable?.let { handler.removeCallbacks(it) }
        detachStopRunnable = null
        runCatching { unregisterReceiver(detachReceiver) }
        sniffer?.stop()
        adbProbe?.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Called by the sniffer/transport layer (via ConnectionStateHolder) on
     * link loss. No auto-retry here (holding/retrying the accessory blocked
     * Android's app chooser on the next plug-in) -- just release everything;
     * [detachReceiver] handles the real "give it 30s in case it's a brief
     * reseat" grace period off the actual USB detach event instead.
     */
    private fun maybeScheduleReconnect(snapshot: ConnectionSnapshot) {
        if (snapshot.phase != ConnectionPhase.ERROR) return
        stopSelfCleanly()
    }

    private fun stopSelfCleanly() {
        session?.stop()
        session = null
        sniffer?.stop()
        adbProbe?.stop()
        adbProbe = null
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
        private const val NOTIFICATION_ID_CAPTURE = 2

        const val ACTION_START_SNIFFER = "dev.caraalink.service.action.START_SNIFFER"
        const val ACTION_START_ADB_PROBE = "dev.caraalink.service.action.START_ADB_PROBE"
        const val ACTION_STOP = "dev.caraalink.service.action.STOP"
        const val ACTION_START_SESSION = "dev.caraalink.service.action.START_SESSION"
        const val ACTION_PROJECTION_GRANTED = "dev.caraalink.service.action.PROJECTION_GRANTED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}
