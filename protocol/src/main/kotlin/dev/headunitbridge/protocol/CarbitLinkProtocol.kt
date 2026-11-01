package dev.headunitbridge.protocol

/**
 * Isolation boundary for the reverse-engineered CarbitLink wire protocol.
 *
 * Intentionally empty. Nothing here is implemented, fabricated, or guessed —
 * every method here must be backed by a confirmed finding in claude/roadmap.md
 * / docs/protocol/, cited to its evidence (APK file, smali/string reference, or
 * a specific usbmon capture). If the real protocol turns out to use per-session
 * crypto or auth we can't replicate, that limitation belongs here, visibly,
 * not stubbed out silently elsewhere.
 */
object CarbitLinkProtocol
