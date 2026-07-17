package me.timschneeberger.rootlessjamesdsp.activity

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.direct.AndroidUsbBitPerfectController
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectPcmPlaybackEngine
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectSourceInspection
import me.timschneeberger.rootlessjamesdsp.audio.direct.DirectSourceInspector

/** Source-owning player. It is separate from playback capture and the system-wide DSP service. */
class DirectPlayerActivity : BaseActivity(), Player.Listener {
    private lateinit var ordinaryPlayer: ExoPlayer
    private lateinit var usbController: AndroidUsbBitPerfectController
    private lateinit var sourceText: TextView
    private lateinit var playbackText: TextView
    private lateinit var usbText: TextView
    private lateinit var mqaText: TextView
    private lateinit var playButton: Button
    private lateinit var usbSwitch: Switch

    private var selectedUri: Uri? = null
    private var inspection: DirectSourceInspection? = null
    private var directPcmEngine: DirectPcmPlaybackEngine? = null
    private var suppressUsbSwitch = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::loadSource)
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
                    .build(),
                true,
            )
            player.volume = 1.0f
        }
        setContentView(buildContent())
        showNoSource()
    }

    override fun onDestroy() {
        stopDirectPcm()
        ordinaryPlayer.removeListener(this)
        ordinaryPlayer.release()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (directPcmEngine != null) return
        playButton.text = getString(
            if (isPlaying) R.string.direct_player_pause else R.string.direct_player_play,
        )
        playbackText.text = getString(
            if (isPlaying) R.string.direct_player_playing_regular else R.string.direct_player_ready,
        )
    }

    override fun onPlayerError(error: PlaybackException) {
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
                    arrayOf("audio/flac", "audio/wav", "audio/x-wav", "audio/mp4", "audio/*"),
                )
            }
        })
        sourceText = TextView(this).apply { setPadding(0, dp(12), 0, dp(12)) }
        content.addView(sourceText)

        playButton = Button(this).apply {
            isEnabled = false
            text = getString(R.string.direct_player_play)
            setOnClickListener(::togglePlayback)
        }
        content.addView(playButton)
        playbackText = TextView(this).apply { setPadding(0, dp(8), 0, dp(20)) }
        content.addView(playbackText)

        content.addView(sectionTitle(getString(R.string.direct_player_usb_section)))
        usbSwitch = Switch(this).apply {
            text = getString(R.string.direct_player_usb_toggle)
            setOnCheckedChangeListener { _, checked ->
                if (suppressUsbSwitch) return@setOnCheckedChangeListener
                if (checked) startVerifiedUsbPcm() else returnToOrdinaryPlayback()
            }
        }
        content.addView(usbSwitch)
        usbText = TextView(this).apply { setPadding(0, dp(8), 0, dp(20)) }
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

    private fun sectionTitle(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun loadSource(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        stopDirectPcm()
        setUsbChecked(false)
        DirectSourceInspector.inspect(this, uri)
            .onSuccess { inspected ->
                selectedUri = uri
                inspection = inspected
                renderSource(inspected)
                mqaText.text = getString(R.string.direct_player_mqa_unavailable)
                usbText.text = if (inspected.decodedPcmFormat == null) {
                    getString(R.string.direct_player_compressed_usb_pending)
                } else {
                    getString(R.string.direct_player_pcm_usb_eligible)
                }
                prepareOrdinaryPlayer(uri, play = false)
                playButton.isEnabled = true
                playbackText.text = getString(R.string.direct_player_ready)
            }
            .onFailure { error ->
                selectedUri = null
                inspection = null
                showNoSource()
                sourceText.text = getString(
                    R.string.direct_player_source_error,
                    error.message ?: error.javaClass.simpleName,
                )
            }
    }

    private fun renderSource(inspected: DirectSourceInspection) {
        val metadata = inspected.metadata
        val quality = when {
            inspected.highResolution -> getString(R.string.direct_player_high_res_lossless)
            inspected.knownLossless -> getString(R.string.direct_player_lossless)
            else -> getString(R.string.direct_player_unverified_format)
        }
        sourceText.text = getString(
            R.string.direct_player_source_format,
            metadata.sampleRate ?: 0,
            metadata.channelCount ?: 0,
            metadata.bitDepth?.toString() ?: getString(R.string.direct_player_unknown),
            metadata.codecIdentifier ?: getString(R.string.direct_player_unknown),
            quality,
        )
    }

    private fun togglePlayback(@Suppress("UNUSED_PARAMETER") view: View) {
        val direct = directPcmEngine
        if (direct != null) {
            val pause = !direct.isPaused()
            direct.setPaused(pause)
            return
        }
        if (ordinaryPlayer.isPlaying) ordinaryPlayer.pause() else ordinaryPlayer.play()
    }

    private fun startVerifiedUsbPcm() {
        val uri = selectedUri
        val format = inspection?.decodedPcmFormat
        if (uri == null || format == null) {
            setUsbChecked(false)
            usbText.text = getString(R.string.direct_player_compressed_usb_pending)
            return
        }

        ordinaryPlayer.stop()
        val preparation = usbController.prepare(format)
        val session = preparation.session
        if (session == null) {
            setUsbChecked(false)
            usbText.text = getString(
                R.string.direct_player_usb_rejected,
                preparation.state.name,
                preparation.reason,
            )
            prepareOrdinaryPlayer(uri, play = false)
            return
        }

        usbText.text = getString(R.string.direct_player_usb_verified_direct, preparation.reason)
        directPcmEngine = DirectPcmPlaybackEngine(
            context = this,
            uri = uri,
            expectedFormat = format,
            session = session,
            eventListener = { event -> runOnUiThread { handleDirectPcmEvent(event) } },
        ).also(DirectPcmPlaybackEngine::start)
    }

    private fun handleDirectPcmEvent(event: DirectPcmPlaybackEngine.Event) {
        when (event) {
            DirectPcmPlaybackEngine.Event.Started,
            DirectPcmPlaybackEngine.Event.Resumed,
            -> {
                playButton.text = getString(R.string.direct_player_pause)
                playbackText.text = getString(R.string.direct_player_playing_verified_usb)
            }
            DirectPcmPlaybackEngine.Event.Paused -> {
                playButton.text = getString(R.string.direct_player_play)
                playbackText.text = getString(R.string.direct_player_paused_verified_usb)
            }
            DirectPcmPlaybackEngine.Event.Completed -> {
                playbackText.text = getString(R.string.direct_player_completed)
                stopDirectPcm()
                setUsbChecked(false)
            }
            DirectPcmPlaybackEngine.Event.Stopped -> Unit
            is DirectPcmPlaybackEngine.Event.Failed -> {
                usbText.text = getString(
                    R.string.direct_player_usb_rejected,
                    "DIRECT_PCM_FAILED",
                    event.reason,
                )
                stopDirectPcm()
                setUsbChecked(false)
                selectedUri?.let { prepareOrdinaryPlayer(it, play = false) }
            }
        }
    }

    private fun returnToOrdinaryPlayback() {
        stopDirectPcm()
        selectedUri?.let { prepareOrdinaryPlayer(it, play = false) }
        usbText.text = if (inspection?.decodedPcmFormat == null) {
            getString(R.string.direct_player_compressed_usb_pending)
        } else {
            getString(R.string.direct_player_pcm_usb_eligible)
        }
    }

    private fun stopDirectPcm() {
        val engine = directPcmEngine
        directPcmEngine = null
        engine?.close()
    }

    private fun prepareOrdinaryPlayer(uri: Uri, play: Boolean) {
        ordinaryPlayer.stop()
        ordinaryPlayer.clearMediaItems()
        ordinaryPlayer.volume = 1.0f
        ordinaryPlayer.setMediaItem(MediaItem.fromUri(uri))
        ordinaryPlayer.prepare()
        ordinaryPlayer.playWhenReady = play
        playButton.text = getString(R.string.direct_player_play)
    }

    private fun showNoSource() {
        sourceText.text = getString(R.string.direct_player_no_source)
        playbackText.text = getString(R.string.direct_player_no_source)
        usbText.text = getString(R.string.direct_player_choose_source_first)
        mqaText.text = getString(R.string.direct_player_mqa_unavailable)
        playButton.isEnabled = false
    }

    private fun setUsbChecked(value: Boolean) {
        suppressUsbSwitch = true
        usbSwitch.isChecked = value
        suppressUsbSwitch = false
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
