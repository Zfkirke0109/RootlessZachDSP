package me.timschneeberger.rootlessjamesdsp.directplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * API 34+ public USB mixer controller.
 *
 * This does not replace Android's HAL and does not claim built-in-speaker or Bluetooth bit-perfect
 * playback. A preference is considered verified only when Android returns the exact attributes that
 * were requested for the selected USB route.
 */
class UsbBitPerfectController(context: Context) : AutoCloseable {
    data class VerifiedSession(
        val redactedDeviceLabel: String,
        val sourceFormat: SourceAudioFormat,
        val mixerFormat: SourceAudioFormat,
    )

    sealed interface ActivationResult {
        data class Activated(val session: VerifiedSession) : ActivationResult
        data class Rejected(
            val reason: UsbBitPerfectFormatMatcher.RejectionReason,
            val detail: String,
        ) : ActivationResult
    }

    data class CapabilitySummary(
        val usbOutputCount: Int,
        val configurableMixerCount: Int,
        val bitPerfectMixerCount: Int,
        val redactedFormats: List<String>,
    )

    private data class ActivePreference(
        val device: AudioDeviceInfo,
        val attributes: AudioMixerAttributes,
        val source: SourceAudioFormat,
    )

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()

    private var activePreference: ActivePreference? = null

    fun capabilitySummary(): CapabilitySummary {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return CapabilitySummary(0, 0, 0, emptyList())
        }
        return capabilitySummaryApi34()
    }

    fun activate(source: SourceAudioFormat): ActivationResult {
        clear()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return ActivationResult.Rejected(
                UsbBitPerfectFormatMatcher.RejectionReason.ANDROID_VERSION_UNSUPPORTED,
                "Android 14 or newer is required for the public preferred-mixer API.",
            )
        }
        return activateApi34(source)
    }

    fun isActive(): Boolean = activePreference != null

    fun handleRemovedDevices(devices: Array<AudioDeviceInfo>): Boolean {
        val current = activePreference ?: return false
        if (devices.none { removed -> removed.id == current.device.id }) return false
        clear()
        return true
    }

    override fun close() {
        clear()
    }

    fun clear(): Boolean {
        val current = activePreference ?: return true
        activePreference = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return runCatching {
            audioManager.clearPreferredMixerAttributes(playbackAttributes, current.device)
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun activateApi34(source: SourceAudioFormat): ActivationResult {
        val usbDevices = usbOutputs()
        if (usbDevices.isEmpty()) {
            return ActivationResult.Rejected(
                UsbBitPerfectFormatMatcher.RejectionReason.NO_USB_OUTPUT,
                "Connect an external USB DAC or USB audio interface.",
            )
        }

        var configurableFound = false
        var bitPerfectFound = false
        usbDevices.forEachIndexed { deviceIndex, device ->
            val supported = runCatching {
                audioManager.getSupportedMixerAttributes(device)
            }.getOrElse { emptyList() }
            if (supported.isNotEmpty()) configurableFound = true
            if (supported.any { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }) {
                bitPerfectFound = true
            }

            val candidates = supported.mapIndexed { index, attributes ->
                UsbBitPerfectFormatMatcher.MixerCandidate(
                    sampleRateHz = attributes.format.sampleRate,
                    channelCount = attributes.format.channelCount,
                    pcmEncoding = attributes.format.encoding,
                    bitPerfect = attributes.mixerBehavior ==
                        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                    platformIndex = index,
                )
            }
            val decision = UsbBitPerfectFormatMatcher.select(source, candidates)
            if (decision !is UsbBitPerfectFormatMatcher.Decision.Match) return@forEachIndexed

            val selected = supported[decision.candidate.platformIndex]
            val accepted = runCatching {
                audioManager.setPreferredMixerAttributes(
                    playbackAttributes,
                    device,
                    selected,
                )
            }.getOrDefault(false)
            if (!accepted) {
                return ActivationResult.Rejected(
                    UsbBitPerfectFormatMatcher.RejectionReason.PLATFORM_REJECTED_PREFERENCE,
                    "Android rejected the exact USB mixer preference.",
                )
            }

            val verified = runCatching {
                audioManager.getPreferredMixerAttributes(playbackAttributes, device)
            }.getOrNull()
            if (verified != selected ||
                verified?.mixerBehavior != AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            ) {
                runCatching {
                    audioManager.clearPreferredMixerAttributes(playbackAttributes, device)
                }
                return ActivationResult.Rejected(
                    UsbBitPerfectFormatMatcher.RejectionReason.PREFERENCE_VERIFICATION_FAILED,
                    "Android did not report the requested mixer preference as active.",
                )
            }

            activePreference = ActivePreference(device, selected, source)
            val mixer = selected.format
            return ActivationResult.Activated(
                VerifiedSession(
                    redactedDeviceLabel = "USB audio output ${deviceIndex + 1}",
                    sourceFormat = source,
                    mixerFormat = SourceAudioFormat(
                        sampleRateHz = mixer.sampleRate,
                        channelCount = mixer.channelCount,
                        pcmEncoding = mixer.encoding,
                        bitDepth = bitDepthForEncoding(mixer.encoding),
                        mimeType = "audio/raw",
                    ),
                ),
            )
        }

        val reason = when {
            !configurableFound -> UsbBitPerfectFormatMatcher.RejectionReason.NO_CONFIGURABLE_MIXERS
            !bitPerfectFound -> UsbBitPerfectFormatMatcher.RejectionReason.NO_BIT_PERFECT_MIXER
            !source.isLinearPcm -> UsbBitPerfectFormatMatcher.RejectionReason.SOURCE_NOT_LINEAR_PCM
            else -> UsbBitPerfectFormatMatcher.RejectionReason.NO_EXACT_FORMAT_MATCH
        }
        val detail = when (reason) {
            UsbBitPerfectFormatMatcher.RejectionReason.SOURCE_NOT_LINEAR_PCM ->
                "The decoder-output PCM format is not verified. Lossless playback remains available without a bit-perfect claim."
            UsbBitPerfectFormatMatcher.RejectionReason.NO_CONFIGURABLE_MIXERS ->
                "The connected USB route reports no configurable mixer formats."
            UsbBitPerfectFormatMatcher.RejectionReason.NO_BIT_PERFECT_MIXER ->
                "The connected USB route reports configurable formats but no bit-perfect behavior."
            else ->
                "No reported bit-perfect USB mixer exactly matches the source sample rate, channel count, and PCM encoding."
        }
        return ActivationResult.Rejected(reason, detail)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun capabilitySummaryApi34(): CapabilitySummary {
        val devices = usbOutputs()
        val attributes = devices.flatMap { device ->
            runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrElse { emptyList() }
        }
        return CapabilitySummary(
            usbOutputCount = devices.size,
            configurableMixerCount = attributes.size,
            bitPerfectMixerCount = attributes.count {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            },
            redactedFormats = attributes.map { attribute ->
                val format = attribute.format
                "${format.sampleRate} Hz, ${format.channelCount} ch, encoding=${format.encoding}, " +
                    if (attribute.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                        "bit-perfect"
                    } else {
                        "default mixer"
                    }
            }.distinct(),
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun usbOutputs(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }

    private fun bitDepthForEncoding(encoding: Int): Int? = when (encoding) {
        android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
        android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
        android.media.AudioFormat.ENCODING_PCM_32BIT,
        android.media.AudioFormat.ENCODING_PCM_FLOAT,
        -> 32
        else -> null
    }
}
