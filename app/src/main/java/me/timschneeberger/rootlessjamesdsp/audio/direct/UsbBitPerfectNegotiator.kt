package me.timschneeberger.rootlessjamesdsp.audio.direct

/** Platform-neutral description used by deterministic JVM tests. */
data class UsbMixerCandidate(
    val format: DirectPcmFormat,
    val bitPerfectBehavior: Boolean,
    val stableOrder: Int,
)

data class UsbMixerSelection(
    val state: DirectPlaybackFidelityState,
    val candidate: UsbMixerCandidate? = null,
    val reason: String,
)

object UsbBitPerfectNegotiator {
    fun select(
        source: DirectPcmFormat,
        candidates: List<UsbMixerCandidate>,
    ): UsbMixerSelection {
        if (candidates.isEmpty()) {
            return UsbMixerSelection(
                state = DirectPlaybackFidelityState.NO_CONFIGURABLE_USB_MIXER,
                reason = "The USB route reported no configurable mixer attributes",
            )
        }

        val exact = candidates.filter { it.format.exactlyMatches(source) }
        if (exact.isEmpty()) {
            return UsbMixerSelection(
                state = DirectPlaybackFidelityState.NO_EXACT_SOURCE_FORMAT,
                reason = "No USB mixer format exactly matches the decoded source",
            )
        }

        val selected = exact
            .asSequence()
            .filter { it.bitPerfectBehavior }
            .sortedBy { it.stableOrder }
            .firstOrNull()
            ?: return UsbMixerSelection(
                state = DirectPlaybackFidelityState.NO_BIT_PERFECT_MIXER,
                reason = "The exact source format is available only with non-bit-perfect behavior",
            )

        return UsbMixerSelection(
            state = DirectPlaybackFidelityState.ELIGIBLE,
            candidate = selected,
            reason = "Exact source format and BIT_PERFECT mixer behavior are eligible for configuration",
        )
    }
}
