package me.timschneeberger.rootlessjamesdsp.audio.capture

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import timber.log.Timber

/** Persistent playback-capture allow/exclude policy using stable package names. */
class CapturePolicyStore(context: Context) {
    enum class Mode { EXCLUDE_SELECTED, ALLOW_SELECTED }
    data class Policy(val mode: Mode, val packageNames: Set<String>, val rawUids: Set<Int>)

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): Policy {
        val mode = runCatching {
            Mode.valueOf(preferences.getString(KEY_MODE, Mode.EXCLUDE_SELECTED.name)!!)
        }.getOrDefault(Mode.EXCLUDE_SELECTED)
        val packages = preferences.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
            .map(String::trim).filter(String::isNotBlank).toSet()
        val uids = preferences.getStringSet(KEY_UIDS, emptySet()).orEmpty()
            .mapNotNull(String::toIntOrNull).filter { it >= 0 }.toSet()
        return Policy(mode, packages, uids)
    }

    fun setMode(mode: Mode) = preferences.edit().putString(KEY_MODE, mode.name).apply()
    fun setPackages(packages: Set<String>) = preferences.edit()
        .putStringSet(KEY_PACKAGES, packages.map(String::trim).filter(String::isNotBlank).toSet()).apply()
    fun setRawUids(uids: Set<Int>) = preferences.edit()
        .putStringSet(KEY_UIDS, uids.filter { it >= 0 }.map(Int::toString).toSet()).apply()

    fun resolveUids(policy: Policy = read()): Set<Int> {
        val packageUids = policy.packageNames.mapNotNull { packageName ->
            try {
                appContext.packageManager.getApplicationInfo(packageName, 0).uid
            } catch (error: PackageManager.NameNotFoundException) {
                Timber.w("Capture policy package unavailable: %s", packageName)
                null
            } catch (error: SecurityException) {
                Timber.w(error, "Capture policy package not visible: %s", packageName)
                null
            }
        }
        return (packageUids + policy.rawUids).filter { it >= 0 }.toSet()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.registerOnSharedPreferenceChangeListener(listener)
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        preferences.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val PREFERENCES_NAME = "rootless_zach_capture_policy"
        const val KEY_MODE = "capture_uid_mode"
        const val KEY_PACKAGES = "capture_packages"
        const val KEY_UIDS = "capture_raw_uids"
    }
}
