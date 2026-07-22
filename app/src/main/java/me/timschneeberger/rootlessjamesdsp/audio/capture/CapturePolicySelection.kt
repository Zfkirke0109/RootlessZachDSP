package me.timschneeberger.rootlessjamesdsp.audio.capture

import java.util.Locale

/** Pure selection state used by the capture-app screen and local unit tests. */
data class CapturePolicySelection(
    val mode: CapturePolicyStore.Mode,
    val packageNames: Set<String>,
) {
    fun normalized(): CapturePolicySelection = copy(
        packageNames = packageNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet(),
    )

    fun withMode(mode: CapturePolicyStore.Mode): CapturePolicySelection =
        copy(mode = mode).normalized()

    fun toggle(packageName: String): CapturePolicySelection {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isBlank()) return normalized()

        val nextPackages = packageNames.toMutableSet()
        if (!nextPackages.add(normalizedPackage)) nextPackages.remove(normalizedPackage)
        return copy(packageNames = nextPackages).normalized()
    }

    companion object {
        fun from(policy: CapturePolicyStore.Policy): CapturePolicySelection =
            CapturePolicySelection(policy.mode, policy.packageNames).normalized()
    }
}

data class VisibleCaptureApplication(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val isEnabled: Boolean,
)

data class CaptureApplicationChoice(
    val packageName: String,
    val label: String,
    val uid: Int?,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val isInstalledAndVisible: Boolean,
)

/**
 * Merges currently visible packages with saved selections. Saved packages that were uninstalled
 * or hidden by Android package visibility stay visible so the user can remove them deliberately.
 */
object CaptureApplicationChoices {
    fun build(
        visibleApplications: List<VisibleCaptureApplication>,
        selectedPackages: Set<String>,
    ): List<CaptureApplicationChoice> {
        val normalizedSelected = selectedPackages
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val visibleByPackage = visibleApplications
            .asSequence()
            .filter { it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .associateBy { it.packageName }

        val choices = visibleByPackage.values.map { app ->
            CaptureApplicationChoice(
                packageName = app.packageName,
                label = app.label.ifBlank { app.packageName },
                uid = app.uid,
                isSystem = app.isSystem,
                isEnabled = app.isEnabled,
                isInstalledAndVisible = true,
            )
        }.toMutableList()

        normalizedSelected
            .filterNot(visibleByPackage::containsKey)
            .forEach { packageName ->
                choices += CaptureApplicationChoice(
                    packageName = packageName,
                    label = packageName,
                    uid = null,
                    isSystem = false,
                    isEnabled = false,
                    isInstalledAndVisible = false,
                )
            }

        return choices.sortedWith(
            compareBy<CaptureApplicationChoice>(
                { it.isInstalledAndVisible },
                { it.isSystem },
                { it.label.lowercase(Locale.ROOT) },
                { it.packageName },
            ),
        )
    }

    fun filter(
        choices: List<CaptureApplicationChoice>,
        query: String,
    ): List<CaptureApplicationChoice> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return choices
        return choices.filter { choice ->
            choice.label.contains(normalizedQuery, ignoreCase = true) ||
                choice.packageName.contains(normalizedQuery, ignoreCase = true)
        }
    }
}
