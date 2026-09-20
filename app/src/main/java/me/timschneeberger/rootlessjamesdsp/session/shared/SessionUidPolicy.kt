package me.timschneeberger.rootlessjamesdsp.session.shared

/**
 * Admission policy shared by session discovery and playback-capture configuration.
 *
 * A null [allowedUids] means "all UIDs except excluded". An empty set means
 * "admit no UIDs", which is intentionally different and is required for an
 * empty application allowlist.
 */
data class SessionUidPolicy(
    val excludedUids: Set<Int> = emptySet(),
    val allowedUids: Set<Int>? = null,
) {
    fun accepts(uid: Int): Boolean =
        uid !in excludedUids && (allowedUids == null || uid in allowedUids)
}
