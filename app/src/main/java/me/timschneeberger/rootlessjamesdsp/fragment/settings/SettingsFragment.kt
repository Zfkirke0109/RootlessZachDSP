package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.CaptureAppsActivity
import me.timschneeberger.rootlessjamesdsp.activity.DirectPlayerActivity
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicyStore
import me.timschneeberger.rootlessjamesdsp.utils.isPlugin
import me.timschneeberger.rootlessjamesdsp.utils.isRootless

class SettingsFragment : SettingsBaseFragment() {
    private val processing by lazy { findPreference<Preference>(getString(R.string.key_audio_format)) }
    private val troubleshooting by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting)) }
    private val diagnostics by lazy { findPreference<Preference>(getString(R.string.key_diagnostics)) }
    private val directPlayer by lazy { findPreference<Preference>(getString(R.string.key_direct_player)) }
    private val captureApps by lazy { findPreference<Preference>(getString(R.string.key_capture_apps)) }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_preferences, rootKey)
        processing?.summary = getString(
            when {
                isRootless() -> R.string.audio_format_summary
                isPlugin() -> R.string.audio_format_summary_plugin
                else -> R.string.audio_format_summary_root
            },
        )
        val rootless = isRootless()
        troubleshooting?.isVisible = rootless
        diagnostics?.isVisible = rootless
        directPlayer?.apply {
            isVisible = rootless
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DirectPlayerActivity::class.java))
                true
            }
        }
        captureApps?.apply {
            isVisible = rootless
            setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), CaptureAppsActivity::class.java))
                true
            }
        }
        updateCaptureAppsSummary()
    }

    override fun onResume() {
        super.onResume()
        updateCaptureAppsSummary()
    }

    private fun updateCaptureAppsSummary() {
        val preference = captureApps ?: return
        if (!isRootless()) return
        val policy = CapturePolicyStore(requireContext()).read()
        val count = policy.packageNames.size
        val packageSummary = resources.getQuantityString(
            when (policy.mode) {
                CapturePolicyStore.Mode.EXCLUDE_SELECTED ->
                    R.plurals.capture_apps_settings_summary_exclude
                CapturePolicyStore.Mode.ALLOW_SELECTED ->
                    R.plurals.capture_apps_settings_summary_allow
            },
            count,
            count,
        )
        preference.summary = if (policy.rawUids.isEmpty()) {
            packageSummary
        } else {
            resources.getQuantityString(
                R.plurals.capture_apps_settings_summary_with_legacy_uids,
                policy.rawUids.size,
                packageSummary,
                policy.rawUids.size,
            )
        }
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}
