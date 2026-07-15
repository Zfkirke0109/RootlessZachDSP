package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import me.timschneeberger.rootlessjamesdsp.MainApplication
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.EngineUtils.toggleEnginePower
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasProjectMediaAppOp
import me.timschneeberger.rootlessjamesdsp.utils.isRootless
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent

class QuickTileService : TileService(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    KoinComponent {

    private val app
        get() = application as MainApplication

    private val preferences: Preferences.App by inject()

    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SERVICE_STARTED,
                Constants.ACTION_SERVICE_STOPPED,
                -> updateState()
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == getString(R.string.key_powered_on)) updateState()
    }

    override fun onStartListening() {
        updateState()
        registerLocalReceiver(
            broadcastReceiver,
            IntentFilter().apply {
                addAction(Constants.ACTION_SERVICE_STARTED)
                addAction(Constants.ACTION_SERVICE_STOPPED)
            },
        )
        preferences.registerOnSharedPreferenceChangeListener(this)
        super.onStartListening()
    }

    override fun onStopListening() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
        unregisterLocalReceiver(broadcastReceiver)
        super.onStopListening()
    }

    private fun isEffectEnabled(): Boolean =
        (isRootless() && BaseAudioProcessorService.activeServices > 0) ||
            (!isRootless() && preferences.get<Boolean>(R.string.key_powered_on))

    private fun updateState() {
        qsTile?.let { tile ->
            tile.state = if (isEffectEnabled()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val toggled = qsTile?.state != Tile.STATE_ACTIVE
        toggleEnginePower(toggled) { intent ->
            val pending = PendingIntent.getActivity(
                app,
                REQUEST_PERMISSION_ACTIVITY,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val projectionPermissionRequired =
                isRootless() && app.mediaProjectionStartIntent == null && !hasProjectMediaAppOp()
            if (projectionPermissionRequired) {
                launchPermissionActivityAndCollapse(intent, pending)
            } else {
                startActivity(intent)
            }
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchPermissionActivityAndCollapse(intent: Intent, pendingIntent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        private const val REQUEST_PERMISSION_ACTIVITY = 1001
    }
}
