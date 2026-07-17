package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android 14+ public-API USB mixer controller for Direct Player.
 *
 * This never touches the playback-capture DSP path. It accepts only exact source-format matches and
 * always clears the preferred mixer attributes when a session closes or its USB route disappears.
 */
class AndroidUsbBitPerfectController(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    data class PreparationResult(
        val state: DirectPlaybackFidelityState,
        val reason: String,
        val session: Session? = null,
    )

    fun prepare(source: DirectPcmFormat): PreparationResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return PreparationResult(
                DirectPlaybackFidelityState.UNSUPPORTED_ANDROID_VERSION,
                "Android 14 or newer is required for public configurable USB mixer attributes",
            )
        }
        return prepareApi34(source)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun prepareApi34(source: DirectPcmFormat): PreparationResult {
        val manager = audioManager ?: return PreparationResult(
            DirectPlaybackFidelityState.NO_USB_OUTPUT,
            "AudioManager is unavailable",
        )
        val usbDevices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter(::isUsbOutput)
            .sortedBy { it.id }
        if (usbDevices.isEmpty()) {
            return PreparationResult(
                DirectPlaybackFidelityState.NO_USB_OUTPUT,
                "No USB audio output is connected",
            )
        }

        var sawMixer = false
        var sawExactFormat = false
        var sawExactBitPerfect = false
        for (device in usbDevices) {
            val mixers = runCatching { manager.getSupportedMixerAttributes(device) }
                .getOrDefault(emptyList())
            if (mixers.isNotEmpty()) sawMixer = true

            val candidates = mixers.mapIndexed { index, mixer ->
                UsbMixerCandidate(
                    format = DirectPcmFormat.fromAudioFormat(mixer.format),
                    bitPerfectBehavior =
                        mixer.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                    stableOrder = index,
                )
            }
            val selection = UsbBitPerfectNegotiator.select(source, candidates)
            sawExactFormat = sawExactFormat || candidates.any { it.format.exactlyMatches(source) }
            sawExactBitPerfect = sawExactBitPerfect || selection.candidate != null
            val chosen = selection.candidate ?: continue
            val mixer = mixers[chosen.stableOrder]
            val result = openSession(manager, device, mixer, source)
            if (result.state == DirectPlaybackFidelityState.READY_BIT_PERFECT) return result
        }

        return when {
            !sawMixer -> PreparationResult(
                DirectPlaybackFidelityState.NO_CONFIGURABLE_USB_MIXER,
                "Connected USB outputs expose no configurable mixer attributes",
            )
            !sawExactFormat -> PreparationResult(
                DirectPlaybackFidelityState.NO_EXACT_SOURCE_FORMAT,
                "No connected USB output exactly supports the decoded source format",
            )
            !sawExactBitPerfect -> PreparationResult(
                DirectPlaybackFidelityState.NO_BIT_PERFECT_MIXER,
                "An exact source format exists, but not with BIT_PERFECT mixer behavior",
            )
            else -> PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_REJECTED,
                "Android rejected or failed to retain the preferred USB mixer attributes",
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun openSession(
        manager: AudioManager,
        device: AudioDeviceInfo,
        mixer: AudioMixerAttributes,
        source: DirectPcmFormat,
    ): PreparationResult {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
            .build()

        val preferred = runCatching {
            manager.setPreferredMixerAttributes(attributes, device, mixer)
        }.getOrDefault(false)
        if (!preferred) {
            return PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_REJECTED,
                "setPreferredMixerAttributes returned false",
            )
        }

        val activePreference = runCatching {
            manager.getPreferredMixerAttributes(attributes, device)
        }.getOrNull()
        if (activePreference != mixer) {
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
            return PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_NOT_ACTIVE,
                "The requested USB mixer attributes were not active after selection",
            )
        }

        val minimumBytes = AudioTrack.getMinBufferSize(
            source.sampleRate,
            source.channelMask,
            source.encoding,
        )
        if (minimumBytes <= 0) {
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
            return PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_REJECTED,
                "AudioTrack reported an invalid minimum buffer size",
            )
        }

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(mixer.format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minimumBytes)
                .build()
        }.getOrElse { error ->
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
            return PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_REJECTED,
                "AudioTrack creation failed: ${error.javaClass.simpleName}",
            )
        }

        if (!track.setPreferredDevice(device)) {
            track.release()
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
            return PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_REJECTED,
                "AudioTrack could not select the USB output device",
            )
        }
        track.setVolume(1.0f)

        val actualFormat = DirectPcmFormat.fromAudioFormat(track.format)
        if (!actualFormat.exactlyMatches(source)) {
            track.release()
            runCatching { manager.clearPreferredMixerAttributes(attributes, device) }
            return PreparationResult(
                DirectPlaybackFidelityState.PREFERENCE_NOT_ACTIVE,
                "AudioTrack actual format did not exactly match the decoded source",
            )
        }

        val session = Session(manager, attributes, device, mixer, track)
        session.registerDisconnectCleanup()
        return PreparationResult(
            DirectPlaybackFidelityState.READY_BIT_PERFECT,
            "Exact source format and BIT_PERFECT USB mixer behavior are active",
            session,
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    class Session internal constructor(
        private val audioManager: AudioManager,
        val attributes: AudioAttributes,
        val device: AudioDeviceInfo,
        val mixerAttributes: AudioMixerAttributes,
        val audioTrack: AudioTrack,
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.any { it.id == device.id }) close()
            }
        }

        internal fun registerDisconnectCleanup() {
            audioManager.registerAudioDeviceCallback(deviceCallback, null)
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            runCatching {
                if (audioTrack.playState != AudioTrack.PLAYSTATE_STOPPED) audioTrack.stop()
            }
            audioTrack.release()
            runCatching { audioManager.clearPreferredMixerAttributes(attributes, device) }
        }
    }

    private fun isUsbOutput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        -> true
        else -> false
    }
}
