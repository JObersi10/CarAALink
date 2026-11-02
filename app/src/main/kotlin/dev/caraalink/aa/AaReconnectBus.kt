package dev.caraalink.aa

/**
 * AA has no mid-session resolution/DPI renegotiation (CONFIRMED -- see
 * claude/roadmap.md's updated research notes citing mrmees/open-android-auto's
 * own protocol docs): changing resolution, orientation, frame rate or DPI
 * requires tearing the session down and reconnecting. [CarSession] sets
 * [onVideoSettingsChanged] while an AA session is active and clears it when
 * not, so [dev.caraalink.settings.SettingsActivity] can trigger an automatic
 * reconnect without either side needing a direct reference to the other
 * (there's no app-wide singleton for the active `CarSession`).
 */
object AaReconnectBus {
    @Volatile
    var onVideoSettingsChanged: (() -> Unit)? = null
}
