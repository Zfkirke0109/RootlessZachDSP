package me.timschneeberger.rootlessjamesdsp.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.webkit.WebView
import androidx.core.content.ContextCompat
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicyStore
import java.time.Instant
import java.util.Locale

/** Builds a local, copy-friendly, redacted compatibility report. */
object CompatibilityDiagnosticsReport {
    fun build(context: Context, includeSelectedPackageNames: Boolean = false): String {
        val app = context.applicationContext
        val audioManager = app.getSystemService(AudioManager::class.java)
        val store = CapturePolicyStore(app)
        val policy = store.read()
        val transport = RootlessZachDiagnostics.latestTransportSnapshot()
        val packageInfo = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val webView = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
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
            } else appendLine("selectedPackages=<redacted>")
            appendLine()
            appendLine("[Audio platform]")
            appendLine("outputSampleRate=${audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE).orEmpty()}")
            appendLine("framesPerBuffer=${audioManager?.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER).orEmpty()}")
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.sortedBy { it.type }
                ?.forEachIndexed { index, device ->
                    appendLine("outputDevice[$index]=type:${device.type},name:${device.productName},sampleRates:${device.sampleRates.joinToString("/")},channels:${device.channelCounts.joinToString("/")}")
                }
            appendLine()
            appendLine("[Rootless transport]")
            appendLine(transport?.compactString() ?: "state=no-telemetry-yet")
            appendLine()
            appendLine("[Privacy]")
            appendLine("This report is generated locally and is not uploaded automatically.")
            appendLine("locale=${Locale.getDefault().toLanguageTag()}")
        }
    }

    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
