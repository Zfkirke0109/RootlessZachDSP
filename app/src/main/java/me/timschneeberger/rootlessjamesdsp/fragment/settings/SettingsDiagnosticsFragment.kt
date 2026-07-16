package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.diagnostics.CompatibilityDiagnosticsReport
import me.timschneeberger.rootlessjamesdsp.diagnostics.RootlessZachDiagnostics
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import java.io.File

/** User-facing view/copy/clear/export controls for app-private rootless diagnostics. */
class SettingsDiagnosticsFragment : SettingsBaseFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.app_diagnostics_preferences, rootKey)

        findPreference<Preference>(getString(R.string.key_diagnostics_recent_events))
            ?.setOnPreferenceClickListener {
                showRecentEvents()
                true
            }

        findPreference<Preference>(getString(R.string.key_diagnostics_copy_summary))
            ?.setOnPreferenceClickListener {
                copySummary()
                true
            }

        findPreference<Preference>(getString(R.string.key_diagnostics_preview_export))
            ?.setOnPreferenceClickListener {
                previewAndExport()
                true
            }

        findPreference<Preference>(getString(R.string.key_diagnostics_clear_history))
            ?.setOnPreferenceClickListener {
                confirmClearHistory()
                true
            }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val transport = RootlessZachDiagnostics.latestTransportSnapshot()
        val file = RootlessZachDiagnostics.latestDiagnosticsFile()
        val recentCount = RootlessZachDiagnostics.readRecentLines(200).size
        findPreference<Preference>(getString(R.string.key_diagnostics_engine_status))?.summary =
            if (transport == null) {
                getString(R.string.rootless_zach_diagnostics_no_telemetry)
            } else {
                buildString {
                    append(transport.compactString())
                    append("\nstructuredEvents=").append(recentCount)
                    append(" activeBytes=").append(file?.takeIf { it.exists() }?.length() ?: 0L)
                }
            }
    }

    private fun showRecentEvents() {
        val events = RootlessZachDiagnostics.readRecentLines(MAX_DIALOG_EVENT_LINES)
        val message = if (events.isEmpty()) {
            getString(R.string.rootless_zach_diagnostics_empty_events)
        } else {
            events.joinToString("\n").take(MAX_DIALOG_CHARACTERS)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rootless_zach_diagnostics_recent_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copySummary() {
        val report = CompatibilityDiagnosticsReport.build(requireContext())
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(getString(R.string.rootless_zach_diagnostics_title), report),
        )
        requireContext().toast(R.string.rootless_zach_diagnostics_copied)
    }

    private fun previewAndExport() {
        val bundle = buildRedactedBundle()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rootless_zach_diagnostics_preview_title)
            .setMessage(bundle.take(MAX_DIALOG_CHARACTERS))
            .setPositiveButton(R.string.rootless_zach_diagnostics_share) { _, _ ->
                shareBundle(bundle)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.rootless_zach_diagnostics_clear_title)
            .setMessage(R.string.rootless_zach_diagnostics_clear_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                RootlessZachDiagnostics.clearHistory()
                findPreference<Preference>(getString(R.string.key_diagnostics_engine_status))?.summary =
                    getString(R.string.rootless_zach_diagnostics_cleared)
                requireContext().toast(R.string.rootless_zach_diagnostics_cleared)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildRedactedBundle(): String = buildString {
        appendLine(getString(R.string.rootless_zach_diagnostics_preview_header))
        appendLine()
        appendLine(CompatibilityDiagnosticsReport.build(requireContext()))
        appendLine()
        appendLine("[Recent structured events]")
        RootlessZachDiagnostics.readRecentLines(MAX_EXPORT_EVENT_LINES).forEach(::appendLine)
    }

    private fun shareBundle(bundle: String) {
        val exportFile = File(requireContext().filesDir, EXPORT_FILE_NAME)
        exportFile.writeText(bundle)
        val uri = FileProvider.getUriForFile(
            requireContext(),
            BuildConfig.APPLICATION_ID + ".dump_provider",
            exportFile,
        )
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.rootless_zach_diagnostics_share_title),
            ),
        )
    }

    companion object {
        private const val EXPORT_FILE_NAME = "rootless_zach_diagnostics_export.txt"
        private const val MAX_DIALOG_EVENT_LINES = 100
        private const val MAX_EXPORT_EVENT_LINES = 1_000
        private const val MAX_DIALOG_CHARACTERS = 60_000
    }
}
