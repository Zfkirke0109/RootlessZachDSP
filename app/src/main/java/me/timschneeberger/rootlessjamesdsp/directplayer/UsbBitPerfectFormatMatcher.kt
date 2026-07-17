package me.timschneeberger.rootlessjamesdsp.directplayer

/** Platform-independent exact-format selection used by the API 34 USB controller and unit tests. */
object UsbBitPerfectFormatMatcher {
    data class MixerCandidate(
        val sampleRateHz: Int,
        val channelCount: Int,
        val pcmEncoding: Int,
        val bitPerfect: Boolean,
        val platformIndex: Int,
    )

    enum class RejectionReason {
        SOURCE_NOT_LINEAR_PCM,
        NO_USB_OUTPUT,
        NO_CONFIGURABLE_MIXERS,
        NO_BIT_PERFECT_MIXER,
        NO_EXACT_FORMAT_MATCH,
        PLATFORM_REJECTED_PREFERENCE,
        PREFERENCE_VERIFICATION_FAILED,
        ANDROID_VERSION_UNSUPPORTED,
        ROUTE_DISCONNECTED,
    }

    sealed interface Decision {
        data class Match(val candidate: MixerCandidate) : Decision
        data class Rejected(val reason: RejectionReason) : Decision
    }

    fun select(
        source: SourceAudioFormat,
        candidates: List<MixerCandidate>,
    ): Decision {
        if (!source.isLinearPcm) {
            return Decision.Rejected(RejectionReason.SOURCE_NOT_LINEAR_PCM)
        }
        if (candidates.isEmpty()) {
            return Decision.Rejected(RejectionReason.NO_CONFIGURABLE_MIXERS)
        }

        val bitPerfect = candidates.filter(MixerCandidate::bitPerfect)
        if (bitPerfect.isEmpty()) {
            return Decision.Rejected(RejectionReason.NO_BIT_PERFECT_MIXER)
        }

        val exact = bitPerfect.firstOrNull { candidate ->
            candidate.sampleRateHz == source.sampleRateHz &&
                candidate.channelCount == source.channelCount &&
                candidate.pcmEncoding == source.pcmEncoding
        }
        return exact?.let(Decision::Match)
            ?: Decision.Rejected(RejectionReason.NO_EXACT_FORMAT_MATCH)
    }
}
