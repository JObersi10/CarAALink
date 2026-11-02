package dev.caraalink.widgets

/**
 * The phone's current media session, as last reported by
 * [MediaListenerService]. A plain volatile holder (same pattern as
 * `CarScreenInfo`/`ConnectionStateHolder`) rather than anything
 * Lifecycle-aware -- the car home screen just polls it on a timer.
 */
object NowPlayingInfo {
    @Volatile var title: String? = null
        private set
    @Volatile var artist: String? = null
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    fun update(title: String?, artist: String?, isPlaying: Boolean) {
        this.title = title
        this.artist = artist
        this.isPlaying = isPlaying
    }

    fun clear() {
        title = null
        artist = null
        isPlaying = false
    }

    /** Null when nothing's playing/paused with known metadata. */
    fun summary(): String? {
        val t = title ?: return null
        return if (artist != null) "$t — $artist" else t
    }
}
