package me.timschneeberger.rootlessjamesdsp.audio.direct

object DirectPlayerFallbackPolicy {
    fun shouldUseOrdinaryPlaybackAfterFailure(
        requestedMode: RequestedPlaybackMode,
        ordinaryPlaybackSupported: Boolean,
        hasSelectedSource: Boolean,
    ): Boolean =
        requestedMode == RequestedPlaybackMode.AUTOMATIC &&
            ordinaryPlaybackSupported &&
            hasSelectedSource
}
