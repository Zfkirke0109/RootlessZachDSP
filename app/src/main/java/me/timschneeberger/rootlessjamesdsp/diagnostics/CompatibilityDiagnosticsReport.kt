package me.timschneeberger.rootlessjamesdsp.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.webkit.WebView
import androidx.core.content.ContextCompat
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicyStore
import java.time.Instant
import java.util.Locale

/** Builds a local, copy-friendly, redacted compatibility report. */
object CompatibilityDiagnosticsReport {
    fun build(
        context: Context,
        includeSelectedPackageNames: Boolean = false,
        includeOutputDeviceNames: Boolean = false,
        recentStructuredEventCount: Int? = null,
    ): String {
        val app = context.applicationContext
        val audioManager = app.getSystemService(AudioManager::class.java)
        val store = CapturePolicyStore(app)
        val policy = store.read()
        val transport = RootlessZachDiagnostics.latestTransportSnapshot()
        val engineSignal = RootlessZachDiagnostics.latestSignalSnapshot()
        val trackInputSignal = RootlessZachDiagnostics.latestTrackInputSignalSnapshot()
        val diagnosticsFile = RootlessZachDiagnostics.latestDiagnosticsFile()
        val countedRecentStructuredEvents =
            recentStructuredEventCount
                ?: RootlessZachDiagnostics.readRecentLines(DEFAULT_REPORT_EVENT_LINES).size
        val packageInfo =
            runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val outputDevices = audioManager
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.sortedBy { it.type }
            .orEmpty()
        val usbMixerCapabilities = queryUsbMixerCapabilities(audioManager, outputDevices)

        return buildString {
            appendLine("RootlessZachDSP compatibility report")
            appendLine("generatedUtc=${Instant.now()}")
            appendLine("applicationId=${BuildConfig.APPLICATION_ID}")
            appendLine("versionName=${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
            appendLine("versionCode=${packageInfo?.longVersionCode ?: BuildConfig.VERSION_CODE.toLong()}")
            appendLine("commit=${BuildConfig.COMMIT_SHA}")
            appendLine()
            appendLine("[Android]")
            appendLine("manufacturer=${Build.MANUFACTURER}")
            appendLine("brand=${Build.BRAND}")
            appendLine("model=${Build.MODEL}")
            appendLine("device=${Build.DEVICE}")
            appendLine("hardware=${Build.HARDWARE}")
            appendLine("sdk=${Build.VERSION.SDK_INT}")
            appendLine("release=${Build.VERSION.RELEASE}")
            appendLine("securityPatch=${Build.VERSION.SECURITY_PATCH}")
            appendLine("buildDisplay=${Build.DISPLAY}")
            appendLine("kernel=${System.getProperty("os.version").orEmpty()}")
            appendLine("webView=${webView?.packageName.orEmpty()} ${webView?.versionName.orEmpty()}")
            appendLine()
            appendLine("[Permissions]")
            appendLine("recordAudio=${hasPermission(app, Manifest.permission.RECORD_AUDIO)}")
            appendLine("postNotifications=${hasPermission(app, Manifest.permission.POST_NOTIFICATIONS)}")
            appendLine()
            appendLine("[Capture policy]")
            appendLine("mode=${policy.mode}")
            appendLine("selectedPackageCount=${policy.packageNames.size}")
            appendLine("selectedRawUidCount=${policy.rawUids.size}")
            if (includeSelectedPackageNames) {
                appendLine("selectedPackages=${policy.packageNames.sorted().joinToString(",")}")
                appendLine("selectedRawUids=${policy.rawUids.sorted().joinToString(",")}")
            } else {
                appendLine("selectedPackages=<redacted>")
                appendLine("selectedRawUids=<redacted>")
            }
            appendLine()
            appendLine("[Audio platform]")
            appendLine(
                "outputSampleRate=" +
                    audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).orEmpty(),
            )
            appendLine(
                "framesPerBuffer=" +
                    audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER).orEmpty(),
            )
            outputDevices.forEachIndexed { index, device ->
                val name = if (includeOutputDeviceNames) device.productName else "<redacted>"
                appendLine(
                    "outputDevice[$index]=type:${device.type},name:$name," +
                        "sampleRates:${device.sampleRates.joinToString("/")}," +
                        "channels:${device.channelCounts.joinToString("/")}",
                )
            }
            appendLine()
            appendLine("[Playback fidelity]")
            appendLine("pipeline=Android playback-capture PCM -> RootlessZachDSP -> AudioTrack")
            appendLine("outputUsage=USAGE_MEDIA")
            appendLine("outputContentType=CONTENT_TYPE_MUSIC")
            appendLine("maximumRootlessSampleRate=48000")
            appendLine("bitPerfect=false")
            appendLine(
                "bitPerfectReason=Playback capture, DSP, crossfades, gain ramps, " +
                    "and AudioTrack prevent bit identity",
            )
            appendLine("highResolutionDriverIncluded=false")
            appendLine(
                "highResolutionDriverReason=An unrooted app cannot replace Samsung kernel " +
                    "or audio-HAL drivers",
            )
            appendLine("usbOutputPresent=${usbMixerCapabilities.usbOutputPresent}")
            appendLine("usbConfigurableMixerCount=${usbMixerCapabilities.configurableMixerCount}")
            appendLine("usbBitPerfectMixerSupported=${usbMixerCapabilities.bitPerfectSupported}")
            appendLine("usbMixerFormats=${usbMixerCapabilities.formats.ifEmpty { "none-reported" }}")
            appendLine("mqaDecoderIncluded=false")
            appendLine("mqaPassthrough=false")
            appendLine("mqaNote=Any licensed source-side decode must happen before PCM capture")
            appendLine(
                "dolbyAtmosCompatibility=media attributes preserved; final system effect policy " +
                    "remains controlled by Samsung",
            )
            appendLine()
            appendLine("[Effect coexistence]")
            appendLine("activeSystemEffectChainObservable=false")
            appendLine("dolbyAtmosStateObservable=false")
            appendLine(
                "warning=Samsung Dolby Atmos, system EQ, volume leveling, or another audio effect " +
                    "may process audio before capture or after AudioTrack input",
            )
            appendLine(
                "recommendedValidation=Compare Atmos off/on/auto per speaker, Bluetooth, and USB " +
                    "route while watching clipping and underruns",
            )
            appendLine()
            appendLine("[Rootless transport]")
            appendLine(transport?.compactString() ?: "state=no-telemetry-yet")
            appendLine()
            appendSignalSection(
                title = "Captured input vs DSP engine output",
                boundary = AudioDiagnosticJson.ENGINE_SIGNAL_MEASUREMENT_BOUNDARY,
                signal = engineSignal,
                outputPrefix = "dspEngineOutput",
            )
            appendLine()
            appendSignalSection(
                title = "Captured input vs AudioTrack input",
                boundary = AudioDiagnosticJson.TRACK_INPUT_SIGNAL_MEASUREMENT_BOUNDARY,
                signal = trackInputSignal,
                outputPrefix = "audioTrackInput",
            )
            appendLine("finalAudioTrackMixMeasured=${trackInputSignal != null}")
            appendLine("finalSystemMixMeasured=false")
            appendLine()
            appendLine("[Structured diagnostics]")
            appendLine("schemaVersion=${AudioDiagnosticJson.SCHEMA_VERSION}")
            appendLine("activeFilePresent=${diagnosticsFile?.exists() == true}")
            appendLine(
                "activeFileBytes=${diagnosticsFile?.takeIf { it.exists() }?.length() ?: 0L}",
            )
            appendLine("recentEventCount=$countedRecentStructuredEvents")
            appendLine("storage=app-private-rotating-jsonl")
            appendLine()
            appendLine("[Privacy]")
            appendLine("This report is generated locally and is not uploaded automatically.")
            appendLine(
                "Output-device names and selected package identities are redacted by default.",
            )
            appendLine(
                "Structured diagnostics contain technical counters only; raw PCM is never stored.",
            )
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
        }
    }

    private fun StringBuilder.appendSignalSection(
        title: String,
        boundary: String,
        signal: me.timschneeberger.rootlessjamesdsp.audio.transport.AudioSignalTelemetry.Snapshot?,
        outputPrefix: String,
    ) {
        appendLine("[$title]")
        appendLine("measurementBoundary=$boundary")
        if (signal == null) {
            appendLine("state=no-signal-telemetry-yet")
            return
        }
        appendLine("sampleCount=${signal.sampleCount}")
        appendLine("capturedInputRms=${signal.inputRms}")
        appendLine("${outputPrefix}Rms=${signal.outputRms}")
        appendLine("capturedInputPeak=${signal.inputPeak}")
        appendLine("${outputPrefix}Peak=${signal.outputPeak}")
        appendLine("capturedInputClippedSamples=${signal.inputClippedSamples}")
        appendLine("${outputPrefix}ClippedSamples=${signal.outputClippedSamples}")
        appendLine("changedSampleRatio=${signal.changedSampleRatio}")
        appendLine("outputChanged=${signal.outputChanged}")
    }

    private fun queryUsbMixerCapabilities(
        audioManager: AudioManager?,
        outputDevices: List<AudioDeviceInfo>,
    ): UsbMixerCapabilities {
        val usbDevices = outputDevices.filter { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        if (audioManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return UsbMixerCapabilities(usbDevices.isNotEmpty(), 0, false, "")
        }
        val attributes = usbDevices.flatMap { device ->
            runCatching { audioManager.getSupportedMixerAttributes(device) }.getOrDefault(emptyList())
        }
        val formats = attributes
            .map { mixer ->
                val format = mixer.format
                "rate:${format.sampleRate},encoding:${format.encoding}," +
                    "channels:${format.channelCount},behavior:${mixer.mixerBehavior}"
            }
            .distinct()
            .sorted()
            .joinToString(";")
        return UsbMixerCapabilities(
            usbOutputPresent = usbDevices.isNotEmpty(),
            configurableMixerCount = attributes.size,
            bitPerfectSupported = attributes.any {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            },
            formats = formats,
        )
    }

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private data class UsbMixerCapabilities(
        val usbOutputPresent: Boolean,
        val configurableMixerCount: Int,
        val bitPerfectSupported: Boolean,
        val formats: String,
    )

    private const val DEFAULT_REPORT_EVENT_LINES = 200
}
