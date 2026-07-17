package me.timschneeberger.rootlessjamesdsp.activity

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.directplayer.MqaSupportState
import me.timschneeberger.rootlessjamesdsp.directplayer.SourceAudioFormat
import me.timschneeberger.rootlessjamesdsp.directplayer.SourceAudioInspector
import me.timschneeberger.rootlessjamesdsp.directplayer.UsbBitPerfectController

/**
 * Separate source-owning player path.
 *
 * This activity does not feed the playback-capture DSP service. It can play open lossless formats
 * through Media3 and optionally request an exact API 34 USB bit-perfect mixer for verified PCM.
 */
class DirectPlayerActivity : BaseActivity(), Player.Listener {
    private lateinit var player: ExoPlayer
    private lateinit var usbController: UsbBitPerfectController
    private lateinit var sourceText: TextView
    private lateinit var playbackText: TextView
    private lateinit var usbText: TextView
    private lateinit var mqaText: TextView
    private lateinit var playButton: Button
    private lateinit var bitPerfectSwitch: Switch

    private var selectedUri: Uri? = null
    private var selectedFormat: SourceAudioFormat? = null
    private var suppressBitPerfectListener = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::loadDocument)
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            if (!usbController.handleRemovedDevices(removedDevices)) return
            runOnUiThread {
                setBitPerfectChecked(false)
                usbText.text = getString(R.string.direct_player_usb_disconnected)
                rebuildPlayback(shouldPlay = player.isPlaying)
            }
        }

        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            runOnUiThread(::refreshUsbCapabilitySummary)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.direct_player_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        usbController = UsbBitPerfectController(applicationContext)
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            exoPlayer.addListener(this)
            exoPlayer.setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            exoPlayer.volume = 1f
        }

        setContentView(buildContentView())
        getSystemService<AudioManager>()?.registerAudioDeviceCallback(audioDeviceCallback, null)
        refreshUsbCapabilitySummary()
        renderMqaState(MqaSupportState.unavailable(carrierReportedByTrustedMetadata = false))
    }

    override fun onDestroy() {
        getSystemService<AudioManager>()?.unregisterAudioDeviceCallback(audioDeviceCallback)
        usbController.close()
        player.removeListener(this)
        player.release()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playButton.text = getString(
            if (isPlaying) R.string.direct_player_pause else R.string.direct_player_play,
        )
        playbackText.text = getString(
            if (isPlaying) R.string.direct_player_playing else R.string.direct_player_ready,
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        playbackText.text = getString(
            R.string.direct_player_error,
            error.errorCodeName,
        )
    }

    private fun buildContentView(): View {
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
                        "audio/mp4",
                        "audio/*",
                    ),
                )
            }
        })
        sourceText = TextView(this).apply {
            text = getString(R.string.direct_player_no_source)
            setPadding(0, dp(12), 0, dp(12))
        }
        content.addView(sourceText)

        playButton = Button(this).apply {
            text = getString(R.string.direct_player_play)
            isEnabled = false
            setOnClickListener {
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        content.addView(playButton)
        playbackText = TextView(this).apply {
            text = getString(R.string.direct_player_no_source)
            setPadding(0, dp(8), 0, dp(20))
        }
        content.addView(playbackText)

        content.addView(sectionTitle(getString(R.string.direct_player_usb_section)))
        bitPerfectSwitch = Switch(this).apply {
            text = getString(R.string.direct_player_usb_toggle)
            setOnCheckedChangeListener { _, isChecked ->
                if (suppressBitPerfectListener) return@setOnCheckedChangeListener
                if (isChecked) enableBitPerfectPreference() else disableBitPerfectPreference()
            }
        }
        content.addView(bitPerfectSwitch)
        usbText = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(20))
        }
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

    private fun loadDocument(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        SourceAudioInspector.inspect(this, uri)
            .onSuccess { inspection ->
                selectedUri = uri
                selectedFormat = inspection.format
                setBitPerfectChecked(false)
                usbController.clear()
                renderSource(inspection.format)
                renderMqaState(
                    MqaSupportState.unavailable(
                        inspection.mqaCarrierReportedByTrustedMetadata,
                    ),
                )
                rebuildPlayback(shouldPlay = false)
                playButton.isEnabled = true
                playbackText.text = getString(R.string.direct_player_ready)
            }
            .onFailure { error ->
                selectedUri = null
                selectedFormat = null
                playButton.isEnabled = false
                sourceText.text = getString(
                    R.string.direct_player_source_error,
                    error.message ?: error.javaClass.simpleName,
                )
            }
    }

    private fun renderSource(format: SourceAudioFormat) {
        val quality = when {
            format.isKnownLossless && format.isHighResolution ->
                getString(R.string.direct_player_high_res_lossless)
            format.isKnownLossless -> getString(R.string.direct_player_lossless)
            else -> getString(R.string.direct_player_unverified_format)
        }
        sourceText.text = getString(
            R.string.direct_player_source_format,
            format.sampleRateHz,
            format.channelCount,
            format.bitDepth?.toString() ?: getString(R.string.direct_player_unknown),
            format.mimeType ?: getString(R.string.direct_player_unknown),
            quality,
        )
    }

    private fun renderMqaState(state: MqaSupportState) {
        mqaText.text = when {
            state.firstUnfoldActive -> getString(R.string.direct_player_mqa_authorized_decoder_active)
            state.carrierPassthroughPossible ->
                getString(R.string.direct_player_mqa_plugin_missing)
            else -> getString(R.string.direct_player_mqa_unavailable)
        }
    }

    private fun enableBitPerfectPreference() {
        val format = selectedFormat
        if (format == null) {
            setBitPerfectChecked(false)
            usbText.text = getString(R.string.direct_player_choose_source_first)
            return
        }

        val wasPlaying = player.isPlaying
        player.stop()
        when (val result = usbController.activate(format)) {
            is UsbBitPerfectController.ActivationResult.Activated -> {
                player.volume = 1f
                usbText.text = getString(
                    R.string.direct_player_usb_verified,
                    result.session.redactedDeviceLabel,
                    result.session.mixerFormat.sampleRateHz,
                    result.session.mixerFormat.channelCount,
                    result.session.mixerFormat.bitDepth?.toString()
                        ?: getString(R.string.direct_player_unknown),
                )
                rebuildPlayback(shouldPlay = wasPlaying)
            }
            is UsbBitPerfectController.ActivationResult.Rejected -> {
                setBitPerfectChecked(false)
                usbText.text = getString(
                    R.string.direct_player_usb_rejected,
                    result.reason.name,
                    result.detail,
                )
                rebuildPlayback(shouldPlay = wasPlaying)
            }
        }
    }

    private fun disableBitPerfectPreference() {
        val wasPlaying = player.isPlaying
        player.stop()
        usbController.clear()
        refreshUsbCapabilitySummary()
        rebuildPlayback(shouldPlay = wasPlaying)
    }

    private fun rebuildPlayback(shouldPlay: Boolean) {
        val uri = selectedUri ?: return
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = shouldPlay
    }

    private fun refreshUsbCapabilitySummary() {
        val summary = usbController.capabilitySummary()
        usbText.text = if (summary.usbOutputCount == 0) {
            getString(R.string.direct_player_no_usb)
        } else {
            getString(
                R.string.direct_player_usb_summary,
                summary.usbOutputCount,
                summary.configurableMixerCount,
                summary.bitPerfectMixerCount,
                summary.redactedFormats.joinToString(separator = "\n").ifBlank {
                    getString(R.string.direct_player_none_reported)
                },
            )
        }
    }

    private fun setBitPerfectChecked(value: Boolean) {
        suppressBitPerfectListener = true
        bitPerfectSwitch.isChecked = value
        suppressBitPerfectListener = false
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
