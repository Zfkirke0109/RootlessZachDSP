package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicyStore
import me.timschneeberger.rootlessjamesdsp.audio.transport.AdaptiveBufferController
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransferResult
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransfers
import me.timschneeberger.rootlessjamesdsp.audio.transport.AudioTransportTelemetry
import me.timschneeberger.rootlessjamesdsp.audio.transport.LinearRamp
import me.timschneeberger.rootlessjamesdsp.audio.transport.WetDryCrossfader
import me.timschneeberger.rootlessjamesdsp.diagnostics.RootlessZachDiagnostics
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.model.rootless.SessionRecordingPolicyEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.OnRootlessSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.IOException
import java.util.Arrays
import java.lang.ref.WeakReference
import kotlin.math.max

@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null
    private val projectionCallbackHandler = Handler(Looper.getMainLooper())
    private val projectionCallback = ProjectionCallback(this)

    @Volatile
    private var recreateRecorderRequested = false

    @Volatile
    private var recreateReason = "configuration changed"

    @Volatile
    private var recorderThread: Thread? = null

    @Volatile
    private var activeAudioRecord: AudioRecord? = null

    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    private lateinit var engine: JamesDspLocalEngine
    private val isRunning: Boolean
        get() = recorderThread != null

    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0

    @Volatile
    private var isProcessorIdle = false

    private var suspendOnIdle = false
    private var excludeRestrictedSessions = false

    @Volatile
    private var isProcessorDisposing = false

    @Volatile
    private var isServiceDisposing = false

    private val transportTelemetry = AudioTransportTelemetry()
    private val capturePolicyStore by lazy { CapturePolicyStore(this) }

    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if (isRunning) requestAudioRecordRecreation("app selection changed")
    }

    private val capturePolicyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (isRunning) requestAudioRecordRecreation("capture allowlist policy changed")
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = applicationContext.getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()

        val filter = IntentFilter().apply {
            addAction(ACTION_PREFERENCES_UPDATED)
            addAction(ACTION_SAMPLE_RATE_UPDATED)
            addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
            addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
            addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        }
        registerLocalReceiver(broadcastReceiver, filter)

        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        capturePolicyStore.registerListener(capturePolicyListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))

        blockedApps.observeForever(blockedAppObserver)
        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)
        recreateRecorderRequested = false

        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand")
        when (intent.action) {
            null -> Timber.wtf("onStartCommand: intent.action is null")
            ACTION_START -> Timber.d("Starting service")
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (isRunning) return START_NOT_STICKY

        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)
        releaseMediaProjection()
        mediaProjection = try {
            mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, mediaProjectionStartIntent!!)
        } catch (error: Exception) {
            Timber.e(error, "Failed to acquire media projection")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            null
        }
        mediaProjection?.registerCallback(projectionCallback, projectionCallbackHandler)
        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        } else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        isServiceDisposing = true
        stopRecording()
        engine.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))
        blockedApps.removeObserver(blockedAppObserver)
        unregisterLocalReceiver(broadcastReceiver)
        releaseMediaProjection()
        mediaProjectionStartIntent = null
        projectionCallback.clear()
        projectionCallbackHandler.removeCallbacksAndMessages(null)
        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()
        capturePolicyStore.unregisterListener(capturePolicyListener)
        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
        stopSelf()
        super.onDestroy()
    }

    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        loadFromPreferences(key)
    }

    private fun onProjectionStopped() {
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

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SAMPLE_RATE_UPDATED -> engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences()
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation("soft reboot requested")
            }
        }
    }

    private val onSessionLossListener = object : RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            if (preferences.get<Boolean>(R.string.key_session_loss_ignore)) return
            if (sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                sessionLossRetryCount++
                Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                sessionManager.pollOnce(false)
                restartRecording()
                return
            }
            sessionLossRetryCount = 0
            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
            this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
            Timber.w("Terminating service due to session loss")
            stopSelf()
        }
    }

    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.isEmpty()
            if (!isProcessorIdle) sessionLossRetryCount = 0
            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")
            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                sessionList.map { it.value }.toTypedArray(),
            )
        }
    }

    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            if (preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) return
            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            if (preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)
            ) {
                startActivity(
                    ServiceNotificationHelper.createAppTroubleshootIntent(
                        this@RootlessAudioProcessorService,
                        mediaProjectionStartIntent,
                        uid,
                        directLaunch = true,
                    ),
                )
                notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
            } else {
                ServiceNotificationHelper.pushAppIssueNotification(
                    this@RootlessAudioProcessorService,
                    mediaProjectionStartIntent,
                    uid,
                )
            }
            this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
            Timber.w("App compatibility problem detected for uid=%d", uid)
            stopSelf()
        }
    }

    private val onSessionPolicyChangeListener =
        object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
            override fun onSessionRecordingPolicyChanged(
                sessionList: HashMap<String, SessionRecordingPolicyEntry>,
                isMinorUpdate: Boolean,
            ) {
                if (!this@RootlessAudioProcessorService.excludeRestrictedSessions) return
                if (!isMinorUpdate) requestAudioRecordRecreation("recording policy changed")
            }
        }

    private fun loadFromPreferences(key: String?) {
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                requestAudioRecordRecreation("restricted-session setting changed")
            }
        }
    }

    fun requestAudioRecordRecreation(reason: String = "configuration changed") {
        if (isProcessorDisposing || isServiceDisposing) return
        recreateReason = reason
        recreateRecorderRequested = true
    }

    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        if (!hasRecordPermission()) {
            Timber.e("Record audio permission missing. Can't record")
            stopSelf()
            return
        }

        val encoding = AudioEncoding.fromInt(
            preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1,
        )
        val configuredBufferSamples = preferences
            .get<Float>(R.string.key_audioformat_buffersize)
            .toInt()
            .coerceIn(MIN_BUFFER_SAMPLES, MAX_BUFFER_SAMPLES)
        val encodingFormat = when (encoding) {
            AudioEncoding.PcmShort -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val bytesPerSample = when (encoding) {
            AudioEncoding.PcmFloat -> Float.SIZE_BYTES
            else -> Short.SIZE_BYTES
        }
        val sampleRate = clamp(determineSamplingRate(), 44_100, 48_000)
        val halBurstSamples = determineBufferSize() * CHANNEL_COUNT
        val minimumAdaptiveSamples = max(
            configuredBufferSamples,
            halBurstSamples * MIN_HAL_BURSTS,
        ).coerceAtMost(MAX_BUFFER_SAMPLES)
        val adaptiveBufferController = AdaptiveBufferController(
            minimumSamples = minimumAdaptiveSamples,
            initialSamples = minimumAdaptiveSamples,
            maximumSamples = MAX_BUFFER_SAMPLES,
        )
        val initialPipeline = try {
            createAudioPipeline(
                encodingFormat,
                sampleRate,
                adaptiveBufferController.currentSamples,
                bytesPerSample,
            )
        } catch (error: Exception) {
            Timber.e(error, "Failed to create initial audio pipeline")
            stopSelf()
            return
        }

        if (engine.sampleRate.toInt() != sampleRate) engine.sampleRate = sampleRate.toFloat()
        isProcessorDisposing = false
        recorderThread = Thread({
            try {
                runAudioLoop(
                    initialPipeline,
                    encoding,
                    encodingFormat,
                    sampleRate,
                    bytesPerSample,
                    adaptiveBufferController,
                )
            } catch (error: IOException) {
                Timber.w(error)
            } catch (error: InterruptedException) {
                Timber.d("Audio transport thread interrupted")
                Thread.currentThread().interrupt()
            } catch (error: Exception) {
                Timber.e(error, "Exception in rootless audio transport")
                CrashlyticsImpl.recordException(error)
                stopSelf()
            } finally {
                activeAudioRecord = null
                activeAudioTrack = null
            }
        }, AUDIO_THREAD_NAME).apply { start() }
    }

    private fun runAudioLoop(
        initialPipeline: AudioPipeline,
        encoding: AudioEncoding,
        encodingFormat: Int,
        sampleRate: Int,
        bytesPerSample: Int,
        adaptiveBufferController: AdaptiveBufferController,
    ) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to set urgent-audio thread priority")
        }

        ServiceNotificationHelper.pushServiceNotification(applicationContext, arrayOf())
        var pipeline = initialPipeline
        var buffers = AudioBuffers(pipeline.bufferSamples)
        var bufferDeadlineNanos = AdaptiveBufferController.bufferDurationNanos(
            pipeline.bufferSamples,
            sampleRate,
            CHANNEL_COUNT,
        )
        val crossfadeSamples = crossfadeSampleCount(sampleRate)
        val recoveryGain = LinearRamp(0f).apply { rampTo(1f, crossfadeSamples) }
        val wetDryCrossfader = WetDryCrossfader(1f)
        var bypassUntilNanos = 0L
        var consecutiveDeadlineMisses = 0
        var rebuildAfterCurrentBuffer = false
        var pendingBufferSamples = pipeline.bufferSamples
        var pendingRebuildReason = ""
        var lastTrackUnderrunCount = 0
        var previousUnderrunCount = 0
        var previousDeadlineMissCount = 0L
        var lastTelemetryPublishNanos = System.nanoTime()

        transportTelemetry.configure(sampleRate, CHANNEL_COUNT, pipeline.bufferSamples)
        activeAudioRecord = pipeline.recorder
        activeAudioTrack = pipeline.track

        fun scheduleRebuild(reason: String, bufferSamples: Int = pipeline.bufferSamples) {
            pendingRebuildReason = reason
            pendingBufferSamples = bufferSamples.coerceIn(MIN_BUFFER_SAMPLES, MAX_BUFFER_SAMPLES)
            rebuildAfterCurrentBuffer = true
            recoveryGain.rampTo(0f, crossfadeSamples.coerceAtMost(pipeline.bufferSamples))
        }

        fun rebuildNow(reason: String, requestedBufferSamples: Int): Boolean {
            releaseAudioPipeline(pipeline)
            activeAudioRecord = null
            activeAudioTrack = null
            var lastError: Exception? = null
            repeat(PIPELINE_REBUILD_ATTEMPTS) { attempt ->
                if (isProcessorDisposing) return false
                try {
                    pipeline = createAudioPipeline(
                        encodingFormat,
                        sampleRate,
                        requestedBufferSamples,
                        bytesPerSample,
                    )
                    buffers = AudioBuffers(pipeline.bufferSamples)
                    bufferDeadlineNanos = AdaptiveBufferController.bufferDurationNanos(
                        pipeline.bufferSamples,
                        sampleRate,
                        CHANNEL_COUNT,
                    )
                    transportTelemetry.configure(sampleRate, CHANNEL_COUNT, pipeline.bufferSamples)
                    transportTelemetry.recordRecovery(reason)
                    lastTrackUnderrunCount = 0
                    activeAudioRecord = pipeline.recorder
                    activeAudioTrack = pipeline.track
                    recoveryGain.setImmediate(0f)
                    recoveryGain.rampTo(1f, crossfadeSamples)
                    Timber.i(
                        "RootlessZach recovery completed: reason=%s bufferSamples=%d attempt=%d",
                        reason,
                        pipeline.bufferSamples,
                        attempt + 1,
                    )
                    return true
                } catch (error: Exception) {
                    lastError = error
                    Timber.w(error, "Audio pipeline rebuild failed: reason=%s attempt=%d", reason, attempt + 1)
                    Thread.sleep(PIPELINE_REBUILD_BACKOFF_MS * (attempt + 1))
                }
            }
            lastError?.let { CrashlyticsImpl.recordException(it) }
            return false
        }

        try {
            while (!isProcessorDisposing) {
                if (recreateRecorderRequested && !rebuildAfterCurrentBuffer) {
                    recreateRecorderRequested = false
                    scheduleRebuild(recreateReason)
                }
                if (isProcessorIdle && suspendOnIdle) {
                    stopPipelineForIdle(pipeline)
                    if (rebuildAfterCurrentBuffer) {
                        if (!rebuildNow(pendingRebuildReason, pendingBufferSamples)) {
                            stopSelf()
                            return
                        }
                        rebuildAfterCurrentBuffer = false
                    }
                    Thread.sleep(IDLE_POLL_MS)
                    continue
                }

                startPipelineIfNeeded(pipeline)
                val readResult = readBuffer(encoding, pipeline.recorder, buffers)
                transportTelemetry.recordRead(readResult)
                if (isProcessorDisposing) break

                if (readResult.errorCode != null) {
                    val decision = adaptiveBufferController.observe(
                        AdaptiveBufferController.Observation(ioError = true),
                    )
                    val next = if (decision.changed) decision.newSamples else pipeline.bufferSamples
                    if (!rebuildNow("AudioRecord error ${readResult.errorCode}", next)) {
                        stopSelf()
                        return
                    }
                    rebuildAfterCurrentBuffer = false
                    continue
                }
                if (!readResult.madeProgress) {
                    if (readResult.zeroProgressCount >= AudioTransfers.DEFAULT_MAX_ZERO_PROGRESS &&
                        !rebuildNow("AudioRecord made no progress", pipeline.bufferSamples)
                    ) {
                        stopSelf()
                        return
                    }
                    continue
                }

                zeroUnreadTail(encoding, buffers, readResult.transferredSamples)
                val dspDesired = System.nanoTime() >= bypassUntilNanos
                if (dspDesired && wetDryCrossfader.targetWet != 1f) {
                    wetDryCrossfader.rampTo(1f, crossfadeSamples)
                } else if (!dspDesired && wetDryCrossfader.targetWet != 0f) {
                    wetDryCrossfader.rampTo(0f, crossfadeSamples)
                }
                val shouldRunDsp = dspDesired || wetDryCrossfader.wet > 0f || !wetDryCrossfader.isSettled
                val processingStartNanos = System.nanoTime()
                var processingFailed = false
                if (shouldRunDsp) {
                    try {
                        processBuffer(encoding, buffers)
                    } catch (error: Exception) {
                        processingFailed = true
                        Timber.e(error, "DSP processing failed; entering fail-open bypass")
                    }
                }
                val processingNanos = System.nanoTime() - processingStartNanos
                if (processingFailed) {
                    wetDryCrossfader.setImmediate(0f)
                    bypassUntilNanos = System.nanoTime() + DSP_BYPASS_COOLDOWN_NANOS
                    copyDryToMixed(encoding, buffers)
                    consecutiveDeadlineMisses = 0
                } else {
                    consecutiveDeadlineMisses = if (processingNanos > bufferDeadlineNanos) {
                        consecutiveDeadlineMisses + 1
                    } else 0
                    if (consecutiveDeadlineMisses >= DEADLINE_MISSES_BEFORE_BYPASS) {
                        bypassUntilNanos = System.nanoTime() + DSP_BYPASS_COOLDOWN_NANOS
                        wetDryCrossfader.rampTo(0f, crossfadeSamples)
                        consecutiveDeadlineMisses = 0
                    }
                    if (shouldRunDsp) mixWetDry(encoding, buffers, wetDryCrossfader)
                    else copyDryToMixed(encoding, buffers)
                }

                applyRecoveryGain(encoding, buffers, recoveryGain)
                transportTelemetry.recordProcessing(
                    processingNanos,
                    bufferDeadlineNanos,
                    wetDryCrossfader.targetWet == 0f || processingFailed,
                )
                val writeResult = writeBuffer(encoding, pipeline.track, buffers)
                transportTelemetry.recordWrite(writeResult)
                val currentUnderruns = pipeline.track.underrunCount
                transportTelemetry.recordUnderrunDelta(
                    (currentUnderruns - lastTrackUnderrunCount).coerceAtLeast(0),
                )
                lastTrackUnderrunCount = currentUnderruns

                if (writeResult.errorCode != null || !writeResult.completed) {
                    val reason = writeResult.errorCode?.let { "AudioTrack error $it" }
                        ?: "AudioTrack partial write ${writeResult.transferredSamples}/${writeResult.requestedSamples}"
                    val decision = adaptiveBufferController.observe(
                        AdaptiveBufferController.Observation(ioError = true),
                    )
                    val next = if (decision.changed) decision.newSamples else pipeline.bufferSamples
                    if (!rebuildNow(reason, next)) {
                        stopSelf()
                        return
                    }
                    rebuildAfterCurrentBuffer = false
                    continue
                }

                if (rebuildAfterCurrentBuffer && recoveryGain.isSettled) {
                    if (!rebuildNow(pendingRebuildReason, pendingBufferSamples)) {
                        stopSelf()
                        return
                    }
                    rebuildAfterCurrentBuffer = false
                    continue
                }

                val now = System.nanoTime()
                if (now - lastTelemetryPublishNanos >= TELEMETRY_INTERVAL_NANOS) {
                    val snapshot = transportTelemetry.snapshot()
                    RootlessZachDiagnostics.publish(snapshot)
                    sendLocalBroadcast(Intent(ACTION_TRANSPORT_TELEMETRY_UPDATED))
                    Timber.i("RZDSP_TELEMETRY %s", snapshot.compactString())
                    val underrunDelta = (snapshot.underrunCount - previousUnderrunCount).coerceAtLeast(0)
                    val deadlineDelta = (snapshot.deadlineMissCount - previousDeadlineMissCount)
                        .coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val decision = adaptiveBufferController.observe(
                        AdaptiveBufferController.Observation(
                            underrunDelta,
                            deadlineDelta,
                            snapshot.processingLoadEwma,
                        ),
                    )
                    if (decision.changed && !rebuildAfterCurrentBuffer) {
                        scheduleRebuild(
                            "adaptive buffer ${decision.reason.name.lowercase()}",
                            decision.newSamples,
                        )
                    }
                    previousUnderrunCount = snapshot.underrunCount
                    previousDeadlineMissCount = snapshot.deadlineMissCount
                    lastTelemetryPublishNanos = now
                }
            }
        } finally {
            releaseAudioPipeline(pipeline)
            activeAudioRecord = null
            activeAudioTrack = null
        }
    }

    private fun readBuffer(
        encoding: AudioEncoding,
        recorder: AudioRecord,
        buffers: AudioBuffers,
    ): AudioTransferResult = if (encoding == AudioEncoding.PcmShort) {
        AudioTransfers.transferFully(buffers.sampleCount, shouldStop = { isProcessorDisposing }) { offset, remaining ->
            recorder.read(buffers.shortDry, offset, remaining, AudioRecord.READ_BLOCKING)
        }
    } else {
        AudioTransfers.transferFully(buffers.sampleCount, shouldStop = { isProcessorDisposing }) { offset, remaining ->
            recorder.read(buffers.floatDry, offset, remaining, AudioRecord.READ_BLOCKING)
        }
    }

    private fun writeBuffer(
        encoding: AudioEncoding,
        track: AudioTrack,
        buffers: AudioBuffers,
    ): AudioTransferResult = if (encoding == AudioEncoding.PcmShort) {
        AudioTransfers.transferFully(buffers.sampleCount, shouldStop = { isProcessorDisposing }) { offset, remaining ->
            track.write(buffers.shortMixed, offset, remaining, AudioTrack.WRITE_BLOCKING)
        }
    } else {
        AudioTransfers.transferFully(buffers.sampleCount, shouldStop = { isProcessorDisposing }) { offset, remaining ->
            track.write(buffers.floatMixed, offset, remaining, AudioTrack.WRITE_BLOCKING)
        }
    }

    private fun processBuffer(encoding: AudioEncoding, buffers: AudioBuffers) {
        if (encoding == AudioEncoding.PcmShort) engine.processInt16(buffers.shortDry, buffers.shortProcessed)
        else engine.processFloat(buffers.floatDry, buffers.floatProcessed)
    }

    private fun mixWetDry(
        encoding: AudioEncoding,
        buffers: AudioBuffers,
        crossfader: WetDryCrossfader,
    ) {
        if (encoding == AudioEncoding.PcmShort) {
            crossfader.mix(buffers.shortDry, buffers.shortProcessed, buffers.shortMixed)
        } else crossfader.mix(buffers.floatDry, buffers.floatProcessed, buffers.floatMixed)
    }

    private fun copyDryToMixed(encoding: AudioEncoding, buffers: AudioBuffers) {
        if (encoding == AudioEncoding.PcmShort) buffers.shortDry.copyInto(buffers.shortMixed)
        else buffers.floatDry.copyInto(buffers.floatMixed)
    }

    private fun applyRecoveryGain(encoding: AudioEncoding, buffers: AudioBuffers, gain: LinearRamp) {
        if (encoding == AudioEncoding.PcmShort) gain.applyInPlace(buffers.shortMixed)
        else gain.applyInPlace(buffers.floatMixed)
    }

    private fun zeroUnreadTail(
        encoding: AudioEncoding,
        buffers: AudioBuffers,
        transferredSamples: Int,
    ) {
        if (transferredSamples >= buffers.sampleCount) return
        if (encoding == AudioEncoding.PcmShort) {
            Arrays.fill(buffers.shortDry, transferredSamples.coerceAtLeast(0), buffers.sampleCount, 0.toShort())
        } else {
            Arrays.fill(buffers.floatDry, transferredSamples.coerceAtLeast(0), buffers.sampleCount, 0f)
        }
    }

    fun stopRecording() {
        val thread = recorderThread ?: return
        isProcessorDisposing = true
        safeStop(activeAudioRecord)
        safeStop(activeAudioTrack)
        thread.interrupt()
        thread.join(AUDIO_THREAD_JOIN_TIMEOUT_MS)
        if (thread.isAlive) Timber.w("Audio transport did not terminate before timeout")
        recorderThread = null
    }

    fun restartRecording() {
        if (isServiceDisposing) return
        stopRecording()
        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
    }

    private fun createAudioPipeline(
        encoding: Int,
        sampleRate: Int,
        bufferSamples: Int,
        bytesPerSample: Int,
    ): AudioPipeline {
        val requestedBytes = bufferSamples * bytesPerSample
        val recorder = buildAudioRecord(encoding, sampleRate, requestedBytes)
        val track = try {
            buildAudioTrack(encoding, sampleRate, requestedBytes)
        } catch (error: Exception) {
            recorder.release()
            throw error
        }
        return AudioPipeline(recorder, track, bufferSamples)
    }

    private fun releaseAudioPipeline(pipeline: AudioPipeline) {
        safeStop(pipeline.recorder)
        safeStop(pipeline.track)
        runCatching { pipeline.recorder.release() }
        runCatching { pipeline.track.release() }
    }

    private fun stopPipelineForIdle(pipeline: AudioPipeline) {
        safeStop(pipeline.recorder)
        safeStop(pipeline.track)
    }

    private fun startPipelineIfNeeded(pipeline: AudioPipeline) {
        if (pipeline.recorder.recordingState == AudioRecord.RECORDSTATE_STOPPED) pipeline.recorder.startRecording()
        if (pipeline.track.playState != AudioTrack.PLAYSTATE_PLAYING) pipeline.track.play()
    }

    private fun safeStop(recorder: AudioRecord?) {
        recorder ?: return
        if (recorder.state != AudioRecord.STATE_INITIALIZED) return
        runCatching {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
        }
    }

    private fun safeStop(track: AudioTrack?) {
        track ?: return
        if (track.state != AudioTrack.STATE_INITIALIZED) return
        runCatching {
            if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
        }
    }

    private fun buildAudioTrack(
        encoding: Int,
        sampleRate: Int,
        requestedBufferBytes: Int,
    ): AudioTrack {
        // Declare the processed output as music so Samsung's normal media-route and
        // Dolby Atmos policy can remain eligible. Capture recursion is still blocked below.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setFlags(0)
            .apply {
                sdkAbove(Build.VERSION_CODES.Q) {
                    setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                }
            }
            .build()
        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()
        val minimumBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding,
        ).coerceAtLeast(1)
        val frameBytes = CHANNEL_COUNT * if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            Short.SIZE_BYTES
        } else Float.SIZE_BYTES
        val bufferBytes = alignToFrame(max(requestedBufferBytes, minimumBytes), frameBytes)
        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributes)
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(
        encoding: Int,
        sampleRate: Int,
        requestedBufferBytes: Int,
    ): AudioRecord {
        if (!hasRecordPermission()) throw IllegalStateException("RECORD_AUDIO not granted")
        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        val restrictedUids = if (excludeRestrictedSessions) {
            sessionManager.sessionPolicyDatabase.getRestrictedUids().toSet()
        } else {
            sessionManager.pollOnce(false)
            emptySet()
        }
        val selectedUids = blockedApps.value.orEmpty().map { it.uid }.toSet()
        val policy = capturePolicyStore.read()
        val policyUids = capturePolicyStore.resolveUids(policy)
        val excludedForSessions = mutableSetOf<Int>()

        when (policy.mode) {
            CapturePolicyStore.Mode.EXCLUDE_SELECTED -> {
                val excluded = restrictedUids + selectedUids + policyUids + Process.myUid()
                excluded.forEach(config::excludeUid)
                excludedForSessions += excluded
                Timber.d("Capture policy=exclude uids=%s", excluded.sorted().joinToString(";"))
            }
            CapturePolicyStore.Mode.ALLOW_SELECTED -> {
                val allowed = policyUids - restrictedUids - selectedUids - Process.myUid()
                val effectiveAllowed = allowed.ifEmpty { setOf(Process.myUid()) }
                effectiveAllowed.forEach(config::addMatchingUid)
                excludedForSessions += restrictedUids
                excludedForSessions += selectedUids
                excludedForSessions += Process.myUid()
                Timber.d("Capture policy=allow uids=%s", effectiveAllowed.sorted().joinToString(";"))
            }
        }

        sessionManager.sessionDatabase.setExcludedUids(excludedForSessions.toTypedArray())
        sessionManager.pollOnce(false)
        val minimumBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_STEREO,
            encoding,
        ).coerceAtLeast(1)
        val frameBytes = CHANNEL_COUNT * if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            Short.SIZE_BYTES
        } else Float.SIZE_BYTES
        val bufferBytes = alignToFrame(max(requestedBufferBytes, minimumBytes), frameBytes)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(config.build())
            .build()
    }

    private fun determineSamplingRate(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull()?.takeUnless { it == 0 } ?: 48_000

    private fun determineBufferSize(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            ?.toIntOrNull()?.takeUnless { it == 0 } ?: 256

    private fun alignToFrame(bytes: Int, frameSizeBytes: Int): Int {
        val remainder = bytes % frameSizeBytes
        return if (remainder == 0) bytes else bytes + frameSizeBytes - remainder
    }

    private fun crossfadeSampleCount(sampleRate: Int): Int =
        ((sampleRate * CROSSFADE_DURATION_MS / 1_000) * CHANNEL_COUNT).coerceAtLeast(CHANNEL_COUNT)

    private data class AudioPipeline(
        val recorder: AudioRecord,
        val track: AudioTrack,
        val bufferSamples: Int,
    )

    private class AudioBuffers(val sampleCount: Int) {
        val floatDry = FloatArray(sampleCount)
        val floatProcessed = FloatArray(sampleCount)
        val floatMixed = FloatArray(sampleCount)
        val shortDry = ShortArray(sampleCount)
        val shortProcessed = ShortArray(sampleCount)
        val shortMixed = ShortArray(sampleCount)
    }

    companion object {
        const val SESSION_LOSS_MAX_RETRIES = 3
        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val ACTION_TRANSPORT_TELEMETRY_UPDATED =
            BuildConfig.APPLICATION_ID + ".rootless.service.TELEMETRY_UPDATED"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"

        private const val AUDIO_THREAD_NAME = "RootlessZachAudio"
        private const val CHANNEL_COUNT = 2
        private const val MIN_BUFFER_SAMPLES = 128
        private const val MAX_BUFFER_SAMPLES = 16_384
        private const val MIN_HAL_BURSTS = 2
        private const val CROSSFADE_DURATION_MS = 24
        private const val DEADLINE_MISSES_BEFORE_BYPASS = 3
        private const val DSP_BYPASS_COOLDOWN_NANOS = 2_000_000_000L
        private const val TELEMETRY_INTERVAL_NANOS = 1_000_000_000L
        private const val IDLE_POLL_MS = 50L
        private const val PIPELINE_REBUILD_ATTEMPTS = 3
        private const val PIPELINE_REBUILD_BACKOFF_MS = 60L
        private const val AUDIO_THREAD_JOIN_TIMEOUT_MS = 1_500L

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            } catch (error: Exception) {
                CrashlyticsImpl.recordException(error)
            }
        }

        fun stop(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStopIntent(context))
            } catch (error: Exception) {
                CrashlyticsImpl.recordException(error)
            }
        }
    }
}
