package dev.caraalink.widgets

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reading another app's currently-playing track needs the same "notification
 * access" permission any notification-reading app needs
 * (`MediaSessionManager.getActiveSessions` requires the caller to itself be
 * an enabled `NotificationListenerService`) -- there is no lighter-weight
 * API for it. This service does nothing with notification content itself;
 * it only exists to hold that permission so [MediaSessionManager] will hand
 * back active sessions, which it then mirrors into [NowPlayingInfo] for the
 * car home screen's "Now playing" widget.
 */
class MediaListenerService : NotificationListenerService() {

    private val logger by lazy { dev.caraalink.diagnostics.DiagnosticLogger(this, "now-playing") }
    private var activeController: MediaController? = null
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            activeController = null
            NowPlayingInfo.clear()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        pickController(controllers ?: emptyList())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        logger.log("listener connected")
        val manager = getSystemService(MediaSessionManager::class.java)
        val component = android.content.ComponentName(this, MediaListenerService::class.java)
        runCatching { manager.addOnActiveSessionsChangedListener(sessionsListener, component) }
            .onFailure { logger.log("addOnActiveSessionsChangedListener failed: $it") }
        runCatching { pickController(manager.getActiveSessions(component)) }
            .onFailure { logger.log("getActiveSessions failed: $it") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        activeController?.unregisterCallback(controllerCallback)
        activeController = null
        NowPlayingInfo.clear()
    }

    // Notification content itself is irrelevant here; only the permission matters.
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun pickController(controllers: List<MediaController>) {
        // Prefer whichever session is actively playing; fall back to the
        // first one that has metadata at all (e.g. paused) over nothing.
        val next = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null }
        if (next === activeController) { publish(); return }
        activeController?.unregisterCallback(controllerCallback)
        activeController = next
        next?.registerCallback(controllerCallback)
        logger.log("active media session -> ${next?.packageName ?: "none"}")
        publish()
    }

    private fun publish() {
        val c = activeController
        val metadata = c?.metadata
        if (c == null || metadata == null) {
            NowPlayingInfo.clear()
            return
        }
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        NowPlayingInfo.update(title, artist, playing)
    }
}
