package me.timschneeberger.rootlessjamesdsp.activity

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.adapter.CaptureAppListItem
import me.timschneeberger.rootlessjamesdsp.adapter.CaptureAppsAdapter
import me.timschneeberger.rootlessjamesdsp.audio.capture.CaptureApplicationChoices
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicySelection
import me.timschneeberger.rootlessjamesdsp.audio.capture.CapturePolicyStore
import me.timschneeberger.rootlessjamesdsp.audio.capture.VisibleCaptureApplication
import me.timschneeberger.rootlessjamesdsp.databinding.ActivityCaptureAppsBinding
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getInstalledApplicationsCompat
import timber.log.Timber

class CaptureAppsActivity : BaseActivity() {
    private lateinit var binding: ActivityCaptureAppsBinding
    private lateinit var policyStore: CapturePolicyStore
    private lateinit var adapter: CaptureAppsAdapter

    private var selection = CapturePolicySelection(
        CapturePolicyStore.Mode.EXCLUDE_SELECTED,
        emptySet(),
    )
    private var pendingRawUids: Set<Int> = emptySet()
    private var visibleApplications: List<VisibleCaptureApplication> = emptyList()
    private var appIcons: Map<String, Drawable?> = emptyMap()
    private var loadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        policyStore = CapturePolicyStore(this)
        val savedPolicy = policyStore.read()
        selection = if (savedInstanceState == null) {
            CapturePolicySelection.from(savedPolicy)
        } else {
            CapturePolicySelection(
                mode = runCatching {
                    CapturePolicyStore.Mode.valueOf(
                        savedInstanceState.getString(STATE_MODE).orEmpty(),
                    )
                }.getOrDefault(savedPolicy.mode),
                packageNames = savedInstanceState
                    .getStringArrayList(STATE_PACKAGES)
                    .orEmpty()
                    .toSet(),
            ).normalized()
        }
        pendingRawUids = savedInstanceState
            ?.getIntArray(STATE_RAW_UIDS)
            ?.toSet()
            ?: savedPolicy.rawUids

        adapter = CaptureAppsAdapter { packageName ->
            selection = selection.toggle(packageName)
            renderList()
        }
        binding.captureAppsList.layoutManager = LinearLayoutManager(this)
        binding.captureAppsList.adapter = adapter

        when (selection.mode) {
            CapturePolicyStore.Mode.EXCLUDE_SELECTED -> binding.captureModeExclude.isChecked = true
            CapturePolicyStore.Mode.ALLOW_SELECTED -> binding.captureModeAllow.isChecked = true
        }
        binding.captureModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.capture_mode_allow -> CapturePolicyStore.Mode.ALLOW_SELECTED
                else -> CapturePolicyStore.Mode.EXCLUDE_SELECTED
            }
            selection = selection.withMode(mode)
            renderStatus()
        }
        binding.captureAppFilter.doAfterTextChanged { renderList() }
        binding.captureClearLegacyUids.setOnClickListener {
            pendingRawUids = emptySet()
            renderLegacyUidState()
            Toast.makeText(this, R.string.capture_legacy_uids_pending_clear, Toast.LENGTH_SHORT).show()
        }
        binding.capturePolicySave.setOnClickListener { savePolicy() }

        renderStatus()
        renderLegacyUidState()
        loadVisibleApplications()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val normalized = selection.normalized()
        outState.putString(STATE_MODE, normalized.mode.name)
        outState.putStringArrayList(STATE_PACKAGES, ArrayList(normalized.packageNames.sorted()))
        outState.putIntArray(STATE_RAW_UIDS, pendingRawUids.sorted().toIntArray())
        super.onSaveInstanceState(outState)
    }

    private fun savePolicy() {
        val normalized = selection.normalized()
        policyStore.setPolicy(
            CapturePolicyStore.Policy(
                mode = normalized.mode,
                packageNames = normalized.packageNames,
                rawUids = pendingRawUids,
            ),
        )
        Toast.makeText(this, R.string.capture_policy_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun loadVisibleApplications() {
        lifecycleScope.launch {
            binding.captureAppsProgress.isVisible = true
            binding.captureAppsList.isVisible = false
            binding.captureAppFilter.isEnabled = false

            val result = withContext(Dispatchers.IO) { queryVisibleApplications() }
            visibleApplications = result.applications
            appIcons = result.icons
            loadFailed = result.failed

            binding.captureAppsProgress.isVisible = false
            binding.captureAppsList.isVisible = true
            binding.captureAppFilter.isEnabled = true
            renderList()
        }
    }

    private fun queryVisibleApplications(): VisibleApplicationsResult {
        val packageManager = packageManager
        val installedApplications = try {
            packageManager.getInstalledApplicationsCompat(0)
        } catch (error: SecurityException) {
            Timber.w(error, "Android denied the visible app list for capture selection")
            return VisibleApplicationsResult(emptyList(), emptyMap(), failed = true)
        } catch (error: RuntimeException) {
            Timber.w(error, "Unable to obtain the visible app list for capture selection")
            return VisibleApplicationsResult(emptyList(), emptyMap(), failed = true)
        }

        val applications = mutableListOf<VisibleCaptureApplication>()
        val icons = mutableMapOf<String, Drawable?>()
        installedApplications
            .asSequence()
            .filter { (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0 }
            .filterNot { it.packageName == packageName }
            .forEach { app ->
                val label = try {
                    app.loadLabel(packageManager).toString()
                } catch (error: RuntimeException) {
                    Timber.w(error, "Unable to load app label for %s", app.packageName)
                    app.packageName
                }
                icons[app.packageName] = try {
                    app.loadIcon(packageManager)
                } catch (error: RuntimeException) {
                    Timber.w(error, "Unable to load app icon for %s", app.packageName)
                    null
                }
                applications += VisibleCaptureApplication(
                    packageName = app.packageName,
                    label = label,
                    uid = app.uid,
                    isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isEnabled = app.enabled,
                )
            }

        return VisibleApplicationsResult(applications, icons, failed = false)
    }

    private fun renderList() {
        val allChoices = CaptureApplicationChoices.build(
            visibleApplications = visibleApplications,
            selectedPackages = selection.packageNames,
        )
        val choices = CaptureApplicationChoices.filter(
            choices = allChoices,
            query = binding.captureAppFilter.text?.toString().orEmpty(),
        )
        adapter.submitList(
            choices.map { choice ->
                CaptureAppListItem(
                    choice = choice,
                    icon = appIcons[choice.packageName],
                    isSelected = choice.packageName in selection.packageNames,
                )
            },
        )
        binding.captureAppsEmpty.isVisible = choices.isEmpty()
        binding.captureAppsEmpty.text = getString(
            if (loadFailed && allChoices.isEmpty()) {
                R.string.capture_apps_load_failed
            } else {
                R.string.capture_apps_empty
            },
        )
        renderStatus()
    }

    private fun renderStatus() {
        val count = selection.packageNames.size
        if (selection.mode == CapturePolicyStore.Mode.ALLOW_SELECTED && count == 0) {
            binding.captureSelectionStatus.setText(R.string.capture_policy_allow_empty_status)
            return
        }
        binding.captureSelectionStatus.text = resources.getQuantityString(
            when (selection.mode) {
                CapturePolicyStore.Mode.EXCLUDE_SELECTED ->
                    R.plurals.capture_policy_exclude_status
                CapturePolicyStore.Mode.ALLOW_SELECTED ->
                    R.plurals.capture_policy_allow_status
            },
            count,
            count,
        )
    }

    private fun renderLegacyUidState() {
        val count = pendingRawUids.size
        binding.captureLegacyUidContainer.visibility = if (count == 0) View.GONE else View.VISIBLE
        binding.captureLegacyUidWarning.text = resources.getQuantityString(
            R.plurals.capture_legacy_uid_warning,
            count,
            count,
        )
    }

    private data class VisibleApplicationsResult(
        val applications: List<VisibleCaptureApplication>,
        val icons: Map<String, Drawable?>,
        val failed: Boolean,
    )

    companion object {
        private const val STATE_MODE = "capture_mode"
        private const val STATE_PACKAGES = "capture_packages"
        private const val STATE_RAW_UIDS = "capture_raw_uids"
    }
}
