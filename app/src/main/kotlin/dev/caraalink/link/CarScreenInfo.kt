package dev.caraalink.link

/**
 * The head unit's real screen shape, learned from its own `CONFIG_CAPTURE`
 * (see [CaptureConfig.width]/[height] -- values the car itself reports, not
 * a guess). Set once CarSession gets that message; read by
 * `AaServiceDiscovery.configFromSettings()` so "auto" orientation can pick
 * a landscape or portrait AA resolution without the user having to tell it
 * which their car is.
 */
object CarScreenInfo {
    @Volatile var isPortrait: Boolean? = null
        private set

    fun update(width: Int, height: Int) {
        if (width > 0 && height > 0) isPortrait = height > width
    }
}
