package me.timschneeberger.rootlessjamesdsp.activity

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.direct.AndroidUsbBitPerfectController
import me.timschneeberger.rootlessjamesdsp.audio.direct.DecodedLosslessPlaybackEngine
import me.timschneeberger.rootlessjamesdsp.audio.direct.DecodedPcmFormatResolver
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPcmPlaybackEngine
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPlaybackFidelityState
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPlayerFallbackPolicy
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectSourceInspection
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectSourceInspector
import me.timschneeberger.rootlessjamesdsp.audio.direct.EnhancedLosslessPlaybackEngine
import me.timschneeberger.rootlessjamesdsp.audio.direct.PcmContainer
import me.timschneeberger.rootlessjamesdsp.audio.direct.RequestedPlaybackMode
import me.timschneeberger.rootlessjamesdsp.audio.direct.ResolvedPlaybackPath
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessIntegrity
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmMetadata
import me.timschneeberger.rootlessjamesdsp.audio.lossless.LosslessPcmSource
import me.timschneeberger.rootlessjamesdsp.player.codec.flac.FlacPcmSource
import me.timschneeberger.rootlessjamesdsp.player.codec.wavpack.WavPackPcmSource
import java.util.Locale

/** Source-owning player. It is separate from playback capture and the system-wide DSP service. */
class DirectPlayerActivity : BaseActivity(), Player.Listener {
    private lateinit var ordinaryPlayer: ExoPlayer
    private lateinit var usbController: AndroidUsbBitPerfectController
    private lateinit var sourceText: TextView
    private lateinit var playbackText: TextView
    private lateinit var outputDetailsText: TextView
    private lateinit var usbText: TextView
    private lateinit var mqaText: TextView
    private lateinit var artworkView: ImageView
    private lateinit var playButton: Button
    private lateinit var correctionButton: Button
    private lateinit var modeStatusText: TextView

    private var selectedUri: Uri? = null
    private var selectedDisplayName: String? = null
    private var correctionUri: Uri? = null
    private var sourceKind: SourceKind = SourceKind.OTHER
    private var inspection: DirectSourceInspection? = null
    private var losslessMetadata: LosslessPcmMetadata? = null
    private var requestedMode = RequestedPlaybackMode.AUTOMATIC
    private var activePath: ResolvedPlaybackPath? = null
    private var directPcmEngine: DirectPcmPlaybackEngine? = null
    private var decodedLosslessEngine: DecodedLosslessPlaybackEngine? = null
    private var enhancedLosslessEngine: EnhancedLosslessPlaybackEngine? = null
    private var directRouteConfirmed = false
    private var startingPlayback = false
    private var loadGeneration = 0
    private var playbackGeneration = 0

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { loadSource(it, resetCorrection = true) }
    }

    private val openCorrectionDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            correctionUri = uri
            selectedUri?.let { loadSource(it, resetCorrection = false) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.direct_player_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        usbController = AndroidUsbBitPerfectController(applicationContext)
        ordinaryPlayer = ExoPlayer.Builder(this).build().also { player ->
            player.addListener(this)
            player.setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_NONE)
                    .build(),
                true,
            )
            player.volume = 1.0f
        }
        setContentView(buildContent())
        showNoSource()
    }

    override fun onDestroy() {
        playbackGeneration++
        stopCustomPlayback()
        ordinaryPlayer.removeListener(this)
        ordinaryPlayer.release()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (hasCustomPlayback() || startingPlayback) return
        if (activePath != ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK && !isPlaying) return
        playButton.text = getString(
            if (isPlaying) R.string.direct_player_pause else R.string.direct_player_play,
        )
        playbackText.text = getString(
            if (isPlaying) R.string.direct_player_playing_regular else R.string.direct_player_ready,
        )
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (activePath != ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK) return
        val format = tracks.groups.asSequence()
            .flatMap { group ->
                (0 until group.length).asSequence()
                    .filter(group::isTrackSelected)
                    .map(group::getTrackFormat)
            }
            .firstOrNull { it.sampleMimeType?.startsWith("audio/") == true }
            ?: return
        outputDetailsText.text = getString(
            R.string.direct_player_ordinary_details,
            format.sampleMimeType ?: getString(R.string.direct_player_unknown),
            format.sampleRate.takeIf { it > 0 }?.toString() ?: getString(R.string.direct_player_unknown),
            format.channelCount.takeIf { it > 0 }?.toString() ?: getString(R.string.direct_player_unknown),
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        if (activePath != ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK || hasCustomPlayback()) return
        startingPlayback = false
        playButton.isEnabled = selectedUri != null
        playbackText.text = getString(R.string.direct_player_error, error.errorCodeName)
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        content.addView(sectionTitle(getString(R.string.direct_player_source_section)))
        content.addView(TextView(this).apply {
            text = getString(R.string.direct_player_truthful_summary)
            setPadding(0, 0, 0, dp(12))
        })
        content.addView(Button(this).apply {
            text = getString(R.string.direct_player_choose_audio)
            setOnClickListener {
                openDocument.launch(
                    arrayOf(
                        "audio/flac",
                        "audio/wav",
                        "audio/x-wav",
                        "audio/wavpack",
                        "audio/x-wavpack",
                        "audio/mp4",
                        "audio/*",
                        // Android's ExternalStorageProvider maps unknown extensions such as
                        // .wv (WavPack) to application/octet-stream, which an audio-only filter
                        // greys out. Allow it here (matching the correction-file picker) so
                        // WavPack sources are selectable; source inspection still validates and
                        // rejects anything that is not actually FLAC/WavPack.
                        "application/octet-stream",
                    ),
                )
            }
        })
        correctionButton = Button(this).apply {
            text = getString(R.string.direct_player_choose_wvc)
            isVisible = false
            setOnClickListener {
                openCorrectionDocument.launch(arrayOf("audio/*", "application/octet-stream"))
            }
        }
        content.addView(correctionButton)
        artworkView = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = getString(R.string.direct_player_embedded_artwork)
            maxHeight = dp(240)
            setPadding(0, dp(12), 0, 0)
            isVisible = false
        }
        content.addView(
            artworkView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        sourceText = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)) }
        content.addView(sourceText)

        content.addView(sectionTitle(getString(R.string.direct_player_mode_section)))
        val automatic = modeButton(
            RequestedPlaybackMode.AUTOMATIC,
            R.string.direct_player_mode_automatic,
        )
        val direct = modeButton(
            RequestedPlaybackMode.BIT_PERFECT_DIRECT,
            R.string.direct_player_mode_direct,
        )
        val enhanced = modeButton(
            RequestedPlaybackMode.ENHANCED_DSP,
            R.string.direct_player_mode_enhanced,
        )
        content.addView(RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(automatic)
            addView(direct)
            addView(enhanced)
            check(automatic.id)
            setOnCheckedChangeListener { _, checkedId ->
                requestedMode = when (checkedId) {
                    direct.id -> RequestedPlaybackMode.BIT_PERFECT_DIRECT
                    enhanced.id -> RequestedPlaybackMode.ENHANCED_DSP
                    else -> RequestedPlaybackMode.AUTOMATIC
                }
                stopAllPlayback(reprepareOrdinary = true)
                renderModeStatus()
            }
        })
        modeStatusText = TextView(this).apply { setPadding(0, dp(4), 0, dp(12)) }
        content.addView(modeStatusText)

        playButton = Button(this).apply {
            isEnabled = false
            text = getString(R.string.direct_player_play)
            setOnClickListener(::togglePlayback)
        }
        content.addView(playButton)
        playbackText = TextView(this).apply { setPadding(0, dp(8), 0, dp(12)) }
        content.addView(playbackText)
        outputDetailsText = TextView(this).apply { setPadding(0, 0, 0, dp(20)) }
        content.addView(outputDetailsText)

        content.addView(sectionTitle(getString(R.string.direct_player_usb_section)))
        usbText = TextView(this).apply { setPadding(0, dp(4), 0, dp(20)) }
        content.addView(usbText)

        content.addView(sectionTitle(getString(R.string.direct_player_mqa_section)))
        mqaText = TextView(this)
        content.addView(mqaText)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun modeButton(mode: RequestedPlaybackMode, textResource: Int): RadioButton =
        RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(textResource)
            tag = mode
        }

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun loadSource(uri: Uri, resetCorrection: Boolean) {
        if (resetCorrection) correctionUri = null
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        stopAllPlayback(reprepareOrdinary = false)
        val generation = ++loadGeneration
        selectedUri = null
        inspection = null
        losslessMetadata = null
        sourceText.setText(R.string.direct_player_inspecting)
        playbackText.setText(R.string.direct_player_inspecting)
        outputDetailsText.text = ""
        usbText.setText(R.string.direct_player_usb_not_evaluated)
        playButton.isEnabled = false
        correctionButton.isEnabled = false
        artworkView.isVisible = false
        val expectedCorrection = correctionUri

        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { inspectSource(uri, expectedCorrection) }
            if (generation != loadGeneration || correctionUri != expectedCorrection) return@launch
            loaded.onSuccess { result ->
                selectedUri = uri
                selectedDisplayName = result.displayName
                sourceKind = result.kind
                inspection = result.inspection
                losslessMetadata = result.losslessMetadata
                correctionButton.isVisible = result.kind == SourceKind.WAVPACK
                correctionButton.isEnabled = true
                correctionButton.text = getString(
                    if (correctionUri == null) {
                        R.string.direct_player_choose_wvc
                    } else {
                        R.string.direct_player_replace_wvc
                    },
                )
                renderSource(result)
                mqaText.text = getString(R.string.direct_player_mqa_unavailable)
                if (result.kind != SourceKind.WAVPACK) {
                    prepareOrdinaryPlayer(uri, play = false)
                }
                playButton.isEnabled = true
                playbackText.text = getString(R.string.direct_player_ready)
                outputDetailsText.setText(R.string.direct_player_output_not_started)
                usbText.text = usbEligibilityText(result)
                renderModeStatus()
            }.onFailure { error ->
                selectedUri = null
                selectedDisplayName = null
                inspection = null
                losslessMetadata = null
                showNoSource()
                correctionButton.isEnabled = true
                sourceText.text = getString(
                    R.string.direct_player_source_error,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }
    }

    private fun inspectSource(uri: Uri, correction: Uri?): Result<LoadedSource> = runCatching {
        val displayName = queryDisplayName(uri)
        val mime = contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        val kind = SourceKind.detect(displayName, mime)
        when (kind) {
            SourceKind.FLAC -> {
                val source = FlacPcmSource.open(this, uri).getOrThrow()
                val metadata = source.use { it.metadata }
                LoadedSource(kind, displayName, null, metadata)
            }
            SourceKind.WAVPACK -> {
                val source = WavPackPcmSource.open(this, uri, correction).getOrThrow()
                val metadata = source.use { it.metadata }
                LoadedSource(kind, displayName, null, metadata)
            }
            else -> {
                val inspected = DirectSourceInspector.inspect(this, uri).getOrThrow()
                LoadedSource(kind, displayName, inspected, null)
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun renderSource(source: LoadedSource) {
        val metadata = source.losslessMetadata
        if (metadata != null) {
            val title = metadata.tags["TITLE"]?.firstOrNull()
            val artist = metadata.tags["ARTIST"]?.joinToString("; ")
            val album = metadata.tags["ALBUM"]?.firstOrNull()
            val identity = listOfNotNull(title, artist, album).joinToString(" · ")
            val replayGain = metadata.replayGain.entries.joinToString { "${it.key}=${it.value}" }
                .ifBlank { getString(R.string.direct_player_none) }
            val transformations = metadata.decodedTransformations.joinToString()
                .ifBlank { getString(R.string.direct_player_none) }
            val container = PcmContainer.forNativeBitDepth(metadata.nativeBitDepth)
            sourceText.text = getString(
                R.string.direct_player_native_source_format,
                source.displayName ?: getString(R.string.direct_player_unknown),
                metadata.codec.name,
                metadata.integrity.displayName(),
                metadata.sampleRate,
                metadata.nativeBitDepth,
                metadata.channelCount,
                metadata.channelLayout,
                container.bitDepth,
                identity.ifBlank { getString(R.string.direct_player_none) },
                replayGain,
                transformations,
            )
            val artwork = metadata.artwork?.bytes
            val bitmap = artwork?.let(::decodeArtwork)
            artworkView.setImageBitmap(bitmap)
            artworkView.isVisible = bitmap != null
            return
        }

        artworkView.isVisible = false
        val inspected = source.inspection ?: return
        val stream = inspected.metadata
        val quality = when {
            inspected.highResolution -> getString(R.string.direct_player_high_res_lossless)
            inspected.knownLossless -> getString(R.string.direct_player_lossless)
            else -> getString(R.string.direct_player_unverified_format)
        }
        sourceText.text = getString(
            R.string.direct_player_source_format,
            stream.sampleRate ?: 0,
            stream.channelCount ?: 0,
            stream.bitDepth?.toString() ?: getString(R.string.direct_player_unknown),
            stream.codecIdentifier ?: getString(R.string.direct_player_unknown),
            quality,
        )
    }

    private fun togglePlayback(@Suppress("UNUSED_PARAMETER") view: View) {
        directPcmEngine?.let {
            it.setPaused(!it.isPaused())
            return
        }
        decodedLosslessEngine?.let {
            it.setPaused(!it.isPaused())
            return
        }
        enhancedLosslessEngine?.let {
            it.setPaused(!it.isPaused())
            return
        }
        if (ordinaryPlayer.isPlaying) {
            ordinaryPlayer.pause()
            return
        }
        if (activePath == ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK) {
            ordinaryPlayer.play()
            return
        }
        startRequestedPlayback()
    }

    private fun startRequestedPlayback() {
        val uri = selectedUri ?: return
        if (startingPlayback) return
        startingPlayback = true
        playButton.isEnabled = false
        playbackText.setText(R.string.direct_player_preparing)

        val metadata = losslessMetadata
        if (metadata != null) {
            val expectedMode = requestedMode
            val expectedKind = sourceKind
            val expectedCorrection = correctionUri
            val expectedLoadGeneration = loadGeneration
            val requestGeneration = ++playbackGeneration
            lifecycleScope.launch {
                val opened = withContext(Dispatchers.IO) {
                    openLosslessSource(uri, expectedKind, expectedCorrection)
                }
                val stillCurrent = requestGeneration == playbackGeneration &&
                    expectedLoadGeneration == loadGeneration &&
                    selectedUri == uri &&
                    requestedMode == expectedMode &&
                    sourceKind == expectedKind &&
                    correctionUri == expectedCorrection
                if (stillCurrent) {
                    startingPlayback = false
                    playButton.isEnabled = selectedUri != null
                }
                opened.onSuccess { source ->
                    if (!stillCurrent) {
                        source.close()
                        return@onSuccess
                    }
                    startLosslessPath(source)
                }.onFailure { error ->
                    if (stillCurrent) {
                        showPlaybackFailure(error)
                    }
                }
            }
            return
        }

        val rawFormat = inspection?.decodedPcmFormat
        when (requestedMode) {
            RequestedPlaybackMode.BIT_PERFECT_DIRECT -> {
                startingPlayback = false
                playButton.isEnabled = true
                if (rawFormat == null) {
                    showPlaybackFailure(IllegalStateException("This source has no exact decoded PCM format for direct mode"))
                } else {
                    startRawDirect(uri, rawFormat)
                }
            }
            RequestedPlaybackMode.ENHANCED_DSP -> {
                startingPlayback = false
                playButton.isEnabled = true
                showPlaybackFailure(
                    IllegalStateException("Enhanced native playback currently supports decoded FLAC and WavPack stereo sources"),
                )
            }
            RequestedPlaybackMode.AUTOMATIC -> {
                startingPlayback = false
                playButton.isEnabled = true
                if (rawFormat != null) {
                    val preparation = usbController.prepare(rawFormat)
                    if (preparation.session != null) {
                        startRawDirect(uri, rawFormat, preparation)
                    } else {
                        startOrdinaryPlayback(
                            "Automatic mode could not open an exact USB contract (${preparation.state.name}); Android playback selected",
                        )
                    }
                } else {
                    startOrdinaryPlayback("Automatic mode selected ordinary Android playback for this source")
                }
            }
        }
    }

    private fun openLosslessSource(
        uri: Uri,
        kind: SourceKind,
        correction: Uri?,
    ): Result<LosslessPcmSource> = when (kind) {
        SourceKind.FLAC -> FlacPcmSource.open(this, uri)
        SourceKind.WAVPACK -> WavPackPcmSource.open(this, uri, correction)
        else -> Result.failure(IllegalStateException("No native lossless decoder is registered for this source"))
    }

    private fun startLosslessPath(source: LosslessPcmSource) {
        val format = DecodedPcmFormatResolver.resolve(source.metadata)
        when (requestedMode) {
            RequestedPlaybackMode.BIT_PERFECT_DIRECT -> {
                if (format == null) {
                    source.close()
                    showPlaybackFailure(IllegalStateException("The decoded PCM channel layout has no exact Android output format"))
                    return
                }
                val preparation = usbController.prepare(format)
                if (preparation.session == null) {
                    source.close()
                    showUsbRejection(preparation)
                    return
                }
                startDecodedDirect(source, format, preparation)
            }
            RequestedPlaybackMode.ENHANCED_DSP -> startEnhanced(source)
            RequestedPlaybackMode.AUTOMATIC -> {
                val preparation = format?.let(usbController::prepare)
                if (format != null && preparation?.session != null) {
                    startDecodedDirect(source, format, preparation)
                } else if (source.metadata.channelCount == 2) {
                    startEnhanced(source)
                    usbText.text = getString(
                        R.string.direct_player_auto_usb_fallback,
                        preparation?.state?.name ?: DirectPlaybackFidelityState.NO_EXACT_SOURCE_FORMAT.name,
                    )
                } else {
                    source.close()
                    if (sourceKind == SourceKind.WAVPACK) {
                        showPlaybackFailure(
                            IllegalStateException(
                                "Multichannel WavPack has no supported ordinary Android fallback; exact USB and stereo JamesDSP were unavailable",
                            ),
                        )
                    } else {
                        startOrdinaryPlayback(
                            "Automatic mode preserved the multichannel layout through Android playback; USB exact mode and stereo JamesDSP were unavailable",
                        )
                    }
                }
            }
        }
    }

    private fun startRawDirect(
        uri: Uri,
        format: me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPcmFormat,
        prepared: AndroidUsbBitPerfectController.PreparationResult? = null,
    ) {
        ordinaryPlayer.stop()
        val preparation = prepared ?: usbController.prepare(format)
        val session = preparation.session
        if (session == null) {
            showUsbRejection(preparation)
            return
        }
        activePath = ResolvedPlaybackPath.ANDROID_BIT_PERFECT_DIRECT
        directRouteConfirmed = false
        renderDirectPreparation(preparation, format, "PCM/WAV extractor")
        val generation = ++playbackGeneration
        lateinit var engine: DirectPcmPlaybackEngine
        engine = DirectPcmPlaybackEngine(
            context = this,
            uri = uri,
            expectedFormat = format,
            session = session,
            eventListener = { event ->
                runOnUiThread {
                    if (generation == playbackGeneration && directPcmEngine === engine) {
                        handleRawDirectEvent(event)
                    }
                }
            },
        )
        directPcmEngine = engine
        engine.start()
    }

    private fun startDecodedDirect(
        source: LosslessPcmSource,
        format: me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPcmFormat,
        preparation: AndroidUsbBitPerfectController.PreparationResult,
    ) {
        val session = preparation.session
        if (session == null) {
            source.close()
            showUsbRejection(preparation)
            return
        }
        ordinaryPlayer.stop()
        activePath = ResolvedPlaybackPath.ANDROID_BIT_PERFECT_DIRECT
        directRouteConfirmed = false
        renderDirectPreparation(preparation, format, source.metadata.codec.name)
        val generation = ++playbackGeneration
        lateinit var engine: DecodedLosslessPlaybackEngine
        engine = DecodedLosslessPlaybackEngine(
            source = source,
            expectedFormat = format,
            session = session,
            eventListener = { event ->
                runOnUiThread {
                    if (generation == playbackGeneration && decodedLosslessEngine === engine) {
                        handleDecodedDirectEvent(event)
                    }
                }
            },
        )
        decodedLosslessEngine = engine
        engine.start()
    }

    private fun startEnhanced(source: LosslessPcmSource) {
        if (source.metadata.channelCount != 2) {
            source.close()
            showPlaybackFailure(
                IllegalStateException("JamesDSP enhanced mode requires stereo; the source was not downmixed"),
            )
            return
        }
        ordinaryPlayer.stop()
        activePath = ResolvedPlaybackPath.ROOTLESS_JAMES_DSP
        usbText.setText(R.string.direct_player_enhanced_usb_status)
        val generation = ++playbackGeneration
        lateinit var engine: EnhancedLosslessPlaybackEngine
        engine = EnhancedLosslessPlaybackEngine(
            context = this,
            source = source,
            eventListener = { event ->
                runOnUiThread {
                    if (generation == playbackGeneration && enhancedLosslessEngine === engine) {
                        handleEnhancedEvent(event)
                    }
                }
            },
        )
        enhancedLosslessEngine = engine
        engine.start()
    }

    private fun handleRawDirectEvent(event: DirectPcmPlaybackEngine.Event) {
        when (event) {
            DirectPcmPlaybackEngine.Event.Started,
            DirectPcmPlaybackEngine.Event.Resumed,
            -> showDirectPlaying()
            DirectPcmPlaybackEngine.Event.RoutedDeviceConfirmed -> showRouteConfirmed()
            DirectPcmPlaybackEngine.Event.Paused -> showDirectPaused()
            is DirectPcmPlaybackEngine.Event.Completed -> {
                directRouteConfirmed = event.routedDeviceConfirmed
                showDirectCompleted()
                stopCustomPlayback()
            }
            DirectPcmPlaybackEngine.Event.Stopped -> Unit
            is DirectPcmPlaybackEngine.Event.Failed -> handleCustomFailure(event.reason)
        }
    }

    private fun handleDecodedDirectEvent(event: DecodedLosslessPlaybackEngine.Event) {
        when (event) {
            DecodedLosslessPlaybackEngine.Event.Started,
            DecodedLosslessPlaybackEngine.Event.Resumed,
            -> showDirectPlaying()
            DecodedLosslessPlaybackEngine.Event.Paused -> showDirectPaused()
            DecodedLosslessPlaybackEngine.Event.Completed -> {
                showDirectCompleted()
                stopCustomPlayback()
            }
            DecodedLosslessPlaybackEngine.Event.Stopped -> Unit
            is DecodedLosslessPlaybackEngine.Event.RouteObserved -> {
                if (event.selectedUsbDevice) showRouteConfirmed() else usbText.text = event.description
            }
            is DecodedLosslessPlaybackEngine.Event.Failed -> handleCustomFailure(event.reason)
        }
    }

    private fun handleEnhancedEvent(event: EnhancedLosslessPlaybackEngine.Event) {
        when (event) {
            is EnhancedLosslessPlaybackEngine.Event.Started -> {
                playButton.text = getString(R.string.direct_player_pause)
                playbackText.setText(R.string.direct_player_playing_enhanced)
                val status = event.status
                outputDetailsText.text = getString(
                    R.string.direct_player_enhanced_details,
                    status.sourceSampleRate,
                    status.sourceBitDepth,
                    status.sourceChannels,
                    status.decodedEncoding,
                    status.audioTrackSampleRate,
                    status.audioTrackEncoding,
                    status.audioTrackChannels,
                    status.activeDspStages.joinToString().ifBlank { getString(R.string.direct_player_none) },
                    status.resamplingStatus,
                )
            }
            EnhancedLosslessPlaybackEngine.Event.Resumed -> {
                playButton.text = getString(R.string.direct_player_pause)
                playbackText.setText(R.string.direct_player_playing_enhanced)
            }
            EnhancedLosslessPlaybackEngine.Event.Paused -> {
                playButton.text = getString(R.string.direct_player_play)
                playbackText.setText(R.string.direct_player_paused_enhanced)
            }
            EnhancedLosslessPlaybackEngine.Event.Completed -> {
                playbackText.setText(R.string.direct_player_completed_enhanced)
                stopCustomPlayback()
            }
            EnhancedLosslessPlaybackEngine.Event.Stopped -> Unit
            is EnhancedLosslessPlaybackEngine.Event.Failed -> handleCustomFailure(event.reason)
        }
    }

    private fun renderDirectPreparation(
        preparation: AndroidUsbBitPerfectController.PreparationResult,
        format: me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPcmFormat,
        decoder: String,
    ) {
        val session = requireNotNull(preparation.session)
        usbText.text = getString(
            R.string.direct_player_usb_android_contract_active,
            preparation.state.name,
            preparation.reason,
        )
        outputDetailsText.text = getString(
            R.string.direct_player_direct_details,
            decoder,
            format.sampleRate,
            format.encoding,
            format.channelCount,
            session.device.productName?.toString() ?: getString(R.string.direct_player_unknown),
            session.device.id,
            "none (bypassed)",
            "none by Android bit-perfect mixer contract",
            preparation.state.name,
        )
    }

    private fun showDirectPlaying() {
        playButton.text = getString(R.string.direct_player_pause)
        playbackText.text = getString(
            if (directRouteConfirmed) {
                R.string.direct_player_playing_routed_usb
            } else {
                R.string.direct_player_playing_android_contract
            },
        )
    }

    private fun showDirectPaused() {
        playButton.text = getString(R.string.direct_player_play)
        playbackText.text = getString(
            if (directRouteConfirmed) {
                R.string.direct_player_paused_routed_usb
            } else {
                R.string.direct_player_paused_android_contract
            },
        )
    }

    private fun showRouteConfirmed() {
        directRouteConfirmed = true
        usbText.text = getString(R.string.direct_player_usb_routed_confirmed)
        playbackText.text = getString(R.string.direct_player_playing_routed_usb)
    }

    private fun showDirectCompleted() {
        playbackText.text = getString(
            if (directRouteConfirmed) {
                R.string.direct_player_completed_route_confirmed
            } else {
                R.string.direct_player_completed_route_unconfirmed
            },
        )
    }

    private fun startOrdinaryPlayback(reason: String) {
        stopCustomPlayback()
        activePath = ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK
        selectedUri?.let { prepareOrdinaryPlayer(it, play = true) }
        usbText.text = reason
        outputDetailsText.setText(R.string.direct_player_ordinary_pending_details)
    }

    private fun showUsbRejection(result: AndroidUsbBitPerfectController.PreparationResult) {
        startingPlayback = false
        playButton.isEnabled = selectedUri != null
        activePath = null
        usbText.text = getString(
            R.string.direct_player_usb_rejected,
            result.state.name,
            result.reason,
        )
        playbackText.setText(R.string.direct_player_ready)
    }

    private fun showPlaybackFailure(error: Throwable) {
        startingPlayback = false
        playButton.isEnabled = selectedUri != null
        activePath = null
        playbackText.text = getString(
            R.string.direct_player_error,
            error.message ?: error.javaClass.simpleName,
        )
    }

    private fun handleCustomFailure(reason: String) {
        stopCustomPlayback()
        startingPlayback = false
        playButton.isEnabled = selectedUri != null
        val shouldFallback = DirectPlayerFallbackPolicy.shouldUseOrdinaryPlaybackAfterFailure(
            requestedMode = requestedMode,
            ordinaryPlaybackSupported = sourceKind != SourceKind.WAVPACK,
            hasSelectedSource = selectedUri != null,
        )
        if (shouldFallback) {
            startOrdinaryPlayback(
                "Automatic mode selected ordinary Android playback after the custom path failed: $reason",
            )
            return
        }
        activePath = null
        usbText.text = getString(R.string.direct_player_usb_rejected, "PLAYBACK_FAILED", reason)
        playbackText.text = getString(R.string.direct_player_error, reason)
    }

    private fun prepareOrdinaryPlayer(uri: Uri, play: Boolean) {
        ordinaryPlayer.stop()
        ordinaryPlayer.clearMediaItems()
        ordinaryPlayer.volume = 1.0f
        ordinaryPlayer.setMediaItem(MediaItem.fromUri(uri))
        ordinaryPlayer.prepare()
        ordinaryPlayer.playWhenReady = play
        playButton.text = getString(if (play) R.string.direct_player_pause else R.string.direct_player_play)
    }

    private fun stopAllPlayback(reprepareOrdinary: Boolean) {
        playbackGeneration++
        startingPlayback = false
        stopCustomPlayback()
        ordinaryPlayer.stop()
        ordinaryPlayer.clearMediaItems()
        activePath = null
        if (reprepareOrdinary && sourceKind != SourceKind.WAVPACK) {
            selectedUri?.let { prepareOrdinaryPlayer(it, play = false) }
        }
    }

    private fun stopCustomPlayback() {
        val raw = directPcmEngine
        val decoded = decodedLosslessEngine
        val enhanced = enhancedLosslessEngine
        directPcmEngine = null
        decodedLosslessEngine = null
        enhancedLosslessEngine = null
        directRouteConfirmed = false
        raw?.close()
        decoded?.close()
        enhanced?.close()
        if (activePath != ResolvedPlaybackPath.ORDINARY_ANDROID_PLAYBACK) activePath = null
        if (::playButton.isInitialized) {
            playButton.text = getString(R.string.direct_player_play)
        }
    }

    private fun hasCustomPlayback(): Boolean =
        directPcmEngine != null || decodedLosslessEngine != null || enhancedLosslessEngine != null

    private fun renderModeStatus() {
        if (!::modeStatusText.isInitialized) return
        modeStatusText.text = when (requestedMode) {
            RequestedPlaybackMode.BIT_PERFECT_DIRECT -> getString(R.string.direct_player_mode_direct_summary)
            RequestedPlaybackMode.ENHANCED_DSP -> getString(R.string.direct_player_mode_enhanced_summary)
            RequestedPlaybackMode.AUTOMATIC -> getString(R.string.direct_player_mode_automatic_summary)
        }
    }

    private fun usbEligibilityText(source: LoadedSource): String {
        val format = source.losslessMetadata?.let(DecodedPcmFormatResolver::resolve)
            ?: source.inspection?.decodedPcmFormat
        return if (format == null) {
            getString(R.string.direct_player_compressed_usb_pending)
        } else {
            getString(R.string.direct_player_pcm_usb_eligible)
        }
    }

    private fun showNoSource() {
        sourceText.text = getString(R.string.direct_player_no_source)
        playbackText.text = getString(R.string.direct_player_no_source)
        outputDetailsText.text = ""
        usbText.text = getString(R.string.direct_player_choose_source_first)
        mqaText.text = getString(R.string.direct_player_mqa_unavailable)
        artworkView.isVisible = false
        correctionButton.isVisible = false
        playButton.isEnabled = false
        if (::modeStatusText.isInitialized) renderModeStatus()
    }

    private fun LosslessIntegrity.displayName(): String = when (this) {
        LosslessIntegrity.LOSSLESS -> "lossless"
        LosslessIntegrity.HYBRID_LOSSY -> "hybrid core only (lossy)"
        LosslessIntegrity.HYBRID_CORRECTED_LOSSLESS -> "hybrid plus correction (lossless)"
    }

    private fun decodeArtwork(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > MAX_ARTWORK_EDGE ||
            bounds.outHeight / sampleSize > MAX_ARTWORK_EDGE
        ) {
            sampleSize *= 2
        }
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        )
    }.getOrNull()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LoadedSource(
        val kind: SourceKind,
        val displayName: String?,
        val inspection: DirectSourceInspection?,
        val losslessMetadata: LosslessPcmMetadata?,
    )

    private enum class SourceKind {
        FLAC,
        WAVPACK,
        RAW_PCM,
        OTHER;

        companion object {
            fun detect(displayName: String?, mime: String?): SourceKind {
                val name = displayName?.lowercase(Locale.ROOT).orEmpty()
                return when {
                    name.endsWith(".flac") || mime == "audio/flac" -> FLAC
                    name.endsWith(".wv") || mime in WAVPACK_MIME_TYPES -> WAVPACK
                    name.endsWith(".wav") || mime in WAV_MIME_TYPES -> RAW_PCM
                    else -> OTHER
                }
            }

            private val WAVPACK_MIME_TYPES = setOf("audio/wavpack", "audio/x-wavpack")
            private val WAV_MIME_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")
        }
    }

    companion object {
        private const val MAX_ARTWORK_EDGE = 1_024
    }
}
