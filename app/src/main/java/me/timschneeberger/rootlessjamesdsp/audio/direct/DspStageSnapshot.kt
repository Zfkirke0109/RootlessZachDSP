package me.timschneeberger.rootlessjamesdsp.audio.direct

import android.content.Context
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import java.io.File

data class DspStageSnapshot(val activeStages: List<String>) {
    val displayValue: String
        get() = activeStages.ifEmpty { listOf("none") }.joinToString(", ")

    companion object {
        fun read(context: Context, appliedNamespaces: Set<String>): DspStageSnapshot {
            val stages = buildList {
                // Output control is always synchronized; even unity gain retains the limiter.
                if (Constants.PREF_OUTPUT in appliedNamespaces) add("output limiter")
                if (active(context, appliedNamespaces, Constants.PREF_COMPANDER, R.string.key_compander_enable)) add("compander")
                if (active(context, appliedNamespaces, Constants.PREF_BASS, R.string.key_bass_enable)) add("bass boost")
                if (active(context, appliedNamespaces, Constants.PREF_EQ, R.string.key_eq_enable)) add("equalizer")
                if (active(context, appliedNamespaces, Constants.PREF_GEQ, R.string.key_geq_enable)) add("graphic EQ")
                if (active(context, appliedNamespaces, Constants.PREF_PEQ, R.string.key_peq_enable)) add("parametric EQ")
                if (active(context, appliedNamespaces, Constants.PREF_REVERB, R.string.key_reverb_enable)) add("reverb")
                if (active(context, appliedNamespaces, Constants.PREF_STEREOWIDE, R.string.key_stereowide_enable)) add("stereo widening")
                if (active(context, appliedNamespaces, Constants.PREF_CROSSFEED, R.string.key_crossfeed_enable)) add("crossfeed")
                if (activeFileStage(context, appliedNamespaces, Constants.PREF_CONVOLVER, R.string.key_convolver_enable, R.string.key_convolver_file)) add("convolver")
                if (active(context, appliedNamespaces, Constants.PREF_TUBE, R.string.key_tube_enable)) add("vacuum tube")
                if (activeFileStage(context, appliedNamespaces, Constants.PREF_DDC, R.string.key_ddc_enable, R.string.key_ddc_file)) add("DDC")
                if (activeFileStage(context, appliedNamespaces, Constants.PREF_LIVEPROG, R.string.key_liveprog_enable, R.string.key_liveprog_file)) add("live program")
            }
            return DspStageSnapshot(stages)
        }

        private fun enabled(context: Context, namespace: String, key: Int): Boolean =
            PreferenceCache.uncachedGet(context, namespace, key, false)

        private fun active(
            context: Context,
            appliedNamespaces: Set<String>,
            namespace: String,
            key: Int,
        ): Boolean = namespace in appliedNamespaces && enabled(context, namespace, key)

        private fun activeFileStage(
            context: Context,
            appliedNamespaces: Set<String>,
            namespace: String,
            enabledKey: Int,
            fileKey: Int,
        ): Boolean {
            if (!active(context, appliedNamespaces, namespace, enabledKey)) return false
            val storedPath = PreferenceCache.uncachedGet(context, namespace, fileKey, "").trim()
            if (storedPath.isEmpty()) return false
            return File(FileLibraryPreference.createFullPathCompat(context, storedPath)).isFile
        }
    }
}
