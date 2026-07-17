#!/usr/bin/env python3
from __future__ import annotations

import base64
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"Expected exactly one match in {path}, found {count} for: {old[:120]!r}")
    write(path, content.replace(old, new, 1))


SERVICE = "app/src/main/java/me/timschneeberger/rootlessjamesdsp/service/RootlessAudioProcessorService.kt"

replace_once(
    SERVICE,
    "import java.util.Arrays\nimport kotlin.math.max",
    "import java.util.Arrays\nimport java.lang.ref.WeakReference\nimport kotlin.math.max",
)

replace_once(
    SERVICE,
    """    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null
""",
    """    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null
    private val projectionCallbackHandler = Handler(Looper.getMainLooper())
    private val projectionCallback = ProjectionCallback(this)
""",
)

replace_once(
    SERVICE,
    "        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!",
    "        mediaProjectionManager = applicationContext.getSystemService<MediaProjectionManager>()!!",
)

replace_once(
    SERVICE,
    """        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)
        mediaProjection = try {
""",
    """        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)
        releaseMediaProjection()
        mediaProjection = try {
""",
)

replace_once(
    SERVICE,
    "        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))",
    "        mediaProjection?.registerCallback(projectionCallback, projectionCallbackHandler)",
)

replace_once(
    SERVICE,
    """        blockedApps.removeObserver(blockedAppObserver)
        unregisterLocalReceiver(broadcastReceiver)
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection = null
        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
""",
    """        blockedApps.removeObserver(blockedAppObserver)
        unregisterLocalReceiver(broadcastReceiver)
        releaseMediaProjection()
        mediaProjectionStartIntent = null
        projectionCallback.clear()
        projectionCallbackHandler.removeCallbacksAndMessages(null)
        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
""",
)

replace_once(
    SERVICE,
    """    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (isServiceDisposing || preferencesVar.get<Boolean>(R.string.key_is_activity_active)) return
            Timber.w("Capture permission revoked. Stopping service.")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))
            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopSelf()
        }
    }
""",
    """    private fun onProjectionStopped() {
        if (isServiceDisposing || preferencesVar.get<Boolean>(R.string.key_is_activity_active)) return
        Timber.w("Capture permission revoked. Stopping service.")
        sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
        toast(getString(R.string.capture_permission_revoked_toast))
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
        stopSelf()
    }

    private fun releaseMediaProjection() {
        val projection = mediaProjection ?: return
        mediaProjection = null
        runCatching { projection.unregisterCallback(projectionCallback) }
            .onFailure { Timber.d(it, "MediaProjection callback was already unregistered") }
        runCatching { projection.stop() }
            .onFailure { Timber.w(it, "Unable to stop MediaProjection cleanly") }
    }

    private class ProjectionCallback(service: RootlessAudioProcessorService) : MediaProjection.Callback() {
        private val serviceReference = WeakReference(service)

        override fun onStop() {
            serviceReference.get()?.onProjectionStopped()
        }

        fun clear() {
            serviceReference.clear()
        }
    }
""",
)

replace_once(
    SERVICE,
    """        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_UNKNOWN)
            .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
""",
    """        // Declare the processed output as music so Samsung's normal media-route and
        // Dolby Atmos policy can remain eligible. Capture recursion is still blocked below.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
""",
)

MANIFEST = "app/src/main/AndroidManifest.xml"
replace_once(
    MANIFEST,
    """        android:icon="@mipmap/ic_dsp_launcher"
        android:label="${label}"
""",
    """        android:icon="@mipmap/ic_dsp_launcher"
        android:roundIcon="@mipmap/ic_dsp_launcher"
        android:label="${label}"
""",
)

COMPAT = "app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/CompatibilityDiagnosticsReport.kt"
replace_once(
    COMPAT,
    """            appendLine()
            appendLine("[Rootless transport]")
""",
    """            appendLine()
            appendLine("[Playback fidelity]")
            appendLine("pipeline=Android playback-capture PCM -> RootlessZachDSP -> AudioTrack")
            appendLine("outputUsage=USAGE_MEDIA")
            appendLine("outputContentType=CONTENT_TYPE_MUSIC")
            appendLine("maximumRootlessSampleRate=48000")
            appendLine("bitPerfect=false")
            appendLine("bitPerfectReason=Playback capture, DSP, crossfades, gain ramps, and AudioTrack prevent bit identity")
            appendLine("highResolutionDriverIncluded=false")
            appendLine("highResolutionDriverReason=An unrooted app cannot replace Samsung kernel or audio-HAL drivers")
            appendLine("mqaDecoderIncluded=false")
            appendLine("mqaPassthrough=false")
            appendLine("mqaNote=Any licensed source-side decode must happen before PCM capture")
            appendLine("dolbyAtmosCompatibility=media attributes preserved; final system effect policy remains controlled by Samsung")
            appendLine()
            appendLine("[Rootless transport]")
""",
)

replace_once(
    COMPAT,
    """            appendLine("[Dry input vs DSP engine output]")
            appendLine("measurementBoundary=${AudioDiagnosticJson.SIGNAL_MEASUREMENT_BOUNDARY}")
""",
    """            appendLine("[Captured input vs DSP engine output]")
            appendLine("measurementBoundary=${AudioDiagnosticJson.SIGNAL_MEASUREMENT_BOUNDARY}")
""",
)

replace_once(COMPAT, 'appendLine("dryInputRms=${signal.inputRms}")', 'appendLine("capturedInputRms=${signal.inputRms}")')
replace_once(COMPAT, 'appendLine("dryInputPeak=${signal.inputPeak}")', 'appendLine("capturedInputPeak=${signal.inputPeak}")')
replace_once(COMPAT, 'appendLine("dryInputClippedSamples=${signal.inputClippedSamples}")', 'appendLine("capturedInputClippedSamples=${signal.inputClippedSamples}")')

JSON_FILE = "app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/AudioDiagnosticJson.kt"
replace_once(
    JSON_FILE,
    'const val SIGNAL_MEASUREMENT_BOUNDARY = "DRY_INPUT_TO_DSP_ENGINE_OUTPUT"',
    'const val SIGNAL_MEASUREMENT_BOUNDARY = "CAPTURED_INPUT_TO_DSP_ENGINE_OUTPUT"',
)

LOCAL_PROFILES = """package me.timschneeberger.rootlessjamesdsp.audio.profile

import me.timschneeberger.rootlessjamesdsp.model.api.AeqSearchResult

/**
 * Conservative local starting templates, not laboratory AutoEQ corrections.
 *
 * Both templates avoid positive gain so they preserve headroom. Users should calibrate by ear or
 * replace them with measured data. They are intentionally labeled unmeasured in the selector.
 */
object LocalEquipmentProfiles {
    const val SOURCE = "RootlessZachDSP local template · unmeasured"

    data class Profile(
        val id: Long,
        val name: String,
        val graphicEq: String,
    )

    private val profiles = listOf(
        Profile(
            id = -10_001L,
            name = "Samsung Galaxy S23 Ultra speakers — safe starting point",
            graphicEq = "GraphicEQ: 20 -12; 31 -10; 50 -8; 80 -4; 125 -1.5; 250 0; 500 -0.5; 1000 0; 2000 -1; 4000 -1.5; 8000 -0.5; 16000 0;",
        ),
        Profile(
            id = -10_002L,
            name = "2024–2025 Jeep Wrangler 4xe premium audio — neutral starting point",
            graphicEq = "GraphicEQ: 20 -2; 31 -1.5; 63 -1; 125 -0.5; 250 -1; 500 0; 1000 0; 2000 -0.5; 4000 -1; 8000 -0.5; 16000 0;",
        ),
    )

    fun allResults(): Array<AeqSearchResult> = profiles.map(::toSearchResult).toTypedArray()

    fun search(query: String): Array<AeqSearchResult> {
        val normalized = query.trim()
        if (normalized.isEmpty()) return allResults()
        return profiles
            .filter { it.name.contains(normalized, ignoreCase = true) }
            .map(::toSearchResult)
            .toTypedArray()
    }

    fun find(id: Long): Profile? = profiles.firstOrNull { it.id == id }

    private fun toSearchResult(profile: Profile) = AeqSearchResult(
        name = profile.name,
        source = SOURCE,
        rank = Int.MAX_VALUE,
        id = profile.id,
    )
}
"""
write(
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/profile/LocalEquipmentProfiles.kt",
    LOCAL_PROFILES,
)

AEQ = "app/src/main/java/me/timschneeberger/rootlessjamesdsp/activity/AeqSelectorActivity.kt"
replace_once(
    AEQ,
    "import me.timschneeberger.rootlessjamesdsp.api.AutoEqClient\n",
    "import me.timschneeberger.rootlessjamesdsp.api.AutoEqClient\nimport me.timschneeberger.rootlessjamesdsp.audio.profile.LocalEquipmentProfiles\n",
)
replace_once(
    AEQ,
    """        val initialResults = savedInstanceState
            ?.getSerializableAs<Array<AeqSearchResult>>(STATE_RESULTS)
            ?: arrayOf()
""",
    """        val initialResults = savedInstanceState
            ?.getSerializableAs<Array<AeqSearchResult>>(STATE_RESULTS)
            ?: LocalEquipmentProfiles.allResults()
""",
)
replace_once(
    AEQ,
    """            autoEqClient.getProfile(
                it.id!!,
""",
    """            LocalEquipmentProfiles.find(it.id!!)?.let { localProfile ->
                val response = Intent()
                response.putExtra(AutoEqSelectorContract.EXTRA_RESULT, localProfile.graphicEq)
                setResult(RESULT_OK, response)
                finish()
                return@ret
            }

            autoEqClient.getProfile(
                it.id!!,
""",
)
replace_once(
    AEQ,
    """    private fun triggerQuery(query: String) {
        isLoading = true
        updateViewStates()

        autoEqClient.queryProfiles(
            query,
            onResponse = { results, isPartial  ->
                binding.partialResultsCard.bodyText = getString(R.string.autoeq_partial_results_warning, results.size)
                binding.partialResultsCard.isVisible = isPartial

                val adapter = binding.profileList.adapter as AutoEqResultAdapter
                adapter.results = results
                adapter.notifyDataSetChanged()

                // Replace hint text in empty view
                binding.emptyViewText.text = getString(R.string.autoeq_no_results)

                isLoading = false
                updateViewStates()
            },
            onFailure = (::handleFailure)
        )
    }
""",
    """    private fun triggerQuery(query: String) {
        isLoading = true
        updateViewStates()
        val localResults = LocalEquipmentProfiles.search(query)

        autoEqClient.queryProfiles(
            query,
            onResponse = { results, isPartial  ->
                val combined = localResults + results
                binding.partialResultsCard.bodyText = getString(R.string.autoeq_partial_results_warning, combined.size)
                binding.partialResultsCard.isVisible = isPartial

                val adapter = binding.profileList.adapter as AutoEqResultAdapter
                adapter.results = combined
                adapter.notifyDataSetChanged()

                // Replace hint text in empty view
                binding.emptyViewText.text = getString(R.string.autoeq_no_results)

                isLoading = false
                updateViewStates()
            },
            onFailure = { error ->
                if (localResults.isEmpty()) {
                    handleFailure(error)
                } else {
                    val adapter = binding.profileList.adapter as AutoEqResultAdapter
                    adapter.results = localResults
                    adapter.notifyDataSetChanged()
                    binding.partialResultsCard.bodyText =
                        "AutoEQ online search failed; showing built-in local templates only."
                    binding.partialResultsCard.isVisible = true
                    isLoading = false
                    updateViewStates()
                }
            }
        )
    }
""",
)

ROADMAP = "docs/FEATURES_20_ROADMAP.md"
roadmap = read(ROADMAP)
marker = "# Delivery order and merge gates"
section = """# Cross-cutting fidelity and device-profile requirements

These requirements apply across the 20 feature milestones and must not be marketed beyond what the
stock Android platform can prove.

## Bit-perfect compatibility mode

- A rootless capture -> DSP -> AudioTrack pipeline is not bit-perfect while processing is active.
- Add a clearly labeled bypass/relinquish mode for users who prefer a source player or USB DAC's own
  direct path. The UI must say "bit-perfect eligibility", never guarantee bit-perfect output.
- Report the exact blockers: capture conversion, sample-rate conversion, DSP, fades, Android mixer,
  route effects, and volume processing.
- Acceptance requires byte/hash comparison only on a direct path where Android exposes one.

## High-resolution output capability probe

- Do not bundle or claim a replacement Samsung audio driver. A normal unrooted APK cannot replace
  the kernel driver, vendor audio HAL, or privileged system policy.
- Probe supported route sample rates and encodings, attempt high-rate AudioTrack creation behind an
  experimental flag, and fall back safely to 48 kHz.
- Keep 96/192 kHz disabled until physical S23 Ultra tests prove stable capture, processing, routing,
  thermal behavior, and no underrun regression.

## MQA compatibility

- Do not claim an MQA decoder or renderer without licensed, auditable decoder technology.
- RootlessZachDSP receives PCM from Android playback capture; any source-side decode must occur
  before capture. The compatibility screen must state that MQA passthrough is unavailable while DSP
  is active.

## Dolby Atmos coexistence

- Rootless output uses media/music AudioAttributes so Samsung's normal media and Dolby policy can
  remain eligible.
- Never silently disable Dolby Atmos. Detect competing effects where possible and warn about
  double-processing or headroom risk.
- Device validation must cover Atmos off/on/auto on speakers, Bluetooth, USB, screen lock, and route
  changes, with captured-input, DSP-output, and final-output measurements.

## Local equipment calibration

- Built-in S23 Ultra speaker and Wrangler 4xe premium-audio entries are conservative unmeasured
  starting templates, not AutoEQ laboratory corrections.
- Preserve headroom by default, show provenance, and allow replacement with measured curves.
- Future calibration should support per-route microphone measurements, target curves, confidence,
  rollback, and automatic app-plus-route profile selection.

---

"""
if section not in roadmap:
    if marker not in roadmap:
        raise RuntimeError("Roadmap delivery marker not found")
    roadmap = roadmap.replace(marker, section + marker, 1)
    write(ROADMAP, roadmap)

HANDOFF = "docs/AGENT_HANDOFF.md"
handoff = read(HANDOFF)
handoff_note = """
## 2026-07-17 physical-device follow-up

- Galaxy S23 Ultra confirmed regular Settings -> Diagnostics access.
- LeakCanary then found a MediaProjection retention chain keeping a destroyed
  RootlessAudioProcessorService alive. The next patch uses the application context for
  MediaProjectionManager, explicitly unregisters and stops MediaProjection, clears handler work,
  and uses a weak callback.
- The user-selected galaxy two-slider image is now the launcher artwork.
- AutoEQ selector includes conservative, explicitly unmeasured local templates for S23 Ultra
  speakers and 2024–2025 Wrangler 4xe premium audio.
- Rootless output declares USAGE_MEDIA / CONTENT_TYPE_MUSIC for normal Samsung media and Dolby
  policy eligibility.
- MQA decoding, guaranteed bit-perfect output, and a replacement high-resolution Samsung driver are
  not claimed. These require capability detection, direct-path validation, licensing where
  applicable, and physical-device proof.
"""
if handoff_note not in handoff:
    write(HANDOFF, handoff.rstrip() + "\n" + handoff_note + "\n")

# Install the exact user-supplied artwork. Temporary base64 chunks are removed by the workflow.
chunk_dir = ROOT / ".github/scripts"
encoded = "".join(
    (chunk_dir / f"rootless_icon.b64.part{index}").read_text(encoding="ascii").strip()
    for index in range(4)
)
icon_bytes = base64.b64decode(encoded)
icon_target = ROOT / "app/src/main/res/drawable-nodpi/rootless_zach_icon_art.webp"
icon_target.parent.mkdir(parents=True, exist_ok=True)
icon_target.write_bytes(icon_bytes)

write(
    "app/src/main/res/drawable/ic_rootless_zach_foreground.xml",
    """<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/rootless_zach_icon_art"
    android:antialias="true"
    android:dither="true"
    android:filter="true"
    android:gravity="fill" />
""",
)

write(
    "app/src/main/res/mipmap-anydpi/ic_dsp_launcher.xml",
    """<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/rootless_zach_icon_art"
    android:antialias="true"
    android:dither="true"
    android:filter="true"
    android:gravity="fill" />
""",
)

print("RootlessZachDSP phase patch applied successfully")
