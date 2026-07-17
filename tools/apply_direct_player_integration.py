#!/usr/bin/env python3
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one anchor, found {count}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content.strip() + "\n", encoding="utf-8")


replace_once(
    "app/build.gradle.kts",
    '    implementation("androidx.mediarouter:mediarouter:1.7.0")\n',
    '    implementation("androidx.mediarouter:mediarouter:1.7.0")\n'
    '    implementation("androidx.media3:media3-exoplayer:1.10.1")\n',
)

replace_once(
    "app/src/main/AndroidManifest.xml",
    '''        <activity
            android:name=".activity.SettingsActivity"
            android:exported="false"
            android:label="@string/title_activity_settings"
            android:theme="@style/Theme.RootlessJamesDSP">
            <meta-data
                android:name="android.support.PARENT_ACTIVITY"
                android:value=".activity.MainActivity" />
        </activity>
''',
    '''        <activity
            android:name=".activity.SettingsActivity"
            android:exported="false"
            android:label="@string/title_activity_settings"
            android:theme="@style/Theme.RootlessJamesDSP">
            <meta-data
                android:name="android.support.PARENT_ACTIVITY"
                android:value=".activity.MainActivity" />
        </activity>

        <activity
            android:name=".activity.DirectPlayerActivity"
            android:exported="false"
            android:label="@string/direct_player_title"
            android:theme="@style/Theme.RootlessJamesDSP"
            android:launchMode="singleTop">
            <meta-data
                android:name="android.support.PARENT_ACTIVITY"
                android:value=".activity.SettingsActivity" />
        </activity>
''',
)

replace_once(
    "app/src/main/res/xml/app_preferences.xml",
    '''    <Preference
        app:key="@string/key_device_profiles"
        app:title="@string/profiles_section_header"
        app:summary="@string/profiles_summary"
        app:icon="@drawable/ic_twotone_cable_24dp"
        app:fragment="me.timschneeberger.rootlessjamesdsp.fragment.settings.SettingsDeviceProfilesFragment" />
''',
    '''    <Preference
        app:key="@string/key_device_profiles"
        app:title="@string/profiles_section_header"
        app:summary="@string/profiles_summary"
        app:icon="@drawable/ic_twotone_cable_24dp"
        app:fragment="me.timschneeberger.rootlessjamesdsp.fragment.settings.SettingsDeviceProfilesFragment" />

    <Preference
        app:key="@string/key_direct_player"
        app:title="@string/direct_player_title"
        app:summary="@string/direct_player_settings_summary"
        app:icon="@drawable/ic_twotone_volume_up_24dp" />
''',
)

write(
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/fragment/settings/SettingsFragment.kt",
    r'''
package me.timschneeberger.rootlessjamesdsp.fragment.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.activity.DirectPlayerActivity
import me.timschneeberger.rootlessjamesdsp.utils.isPlugin
import me.timschneeberger.rootlessjamesdsp.utils.isRootless

class SettingsFragment : SettingsBaseFragment() {
    private val processing by lazy { findPreference<Preference>(getString(R.string.key_audio_format)) }
    private val troubleshooting by lazy { findPreference<Preference>(getString(R.string.key_troubleshooting)) }
    private val diagnostics by lazy { findPreference<Preference>(getString(R.string.key_diagnostics)) }
    private val directPlayer by lazy { findPreference<Preference>(getString(R.string.key_direct_player)) }

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
    }

    companion object {
        fun newInstance() = SettingsFragment()
    }
}
''',
)

pcm_metadata_paths = (
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/direct/DirectPcmPlaybackEngine.kt",
    "app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/direct/DirectSourceInspector.kt",
)
for path in pcm_metadata_paths:
    replace_once(
        path,
        "MediaFormat.METADATA_KEY_BITS_PER_SAMPLE",
        '"bits-per-sample"',
    )

# These two files were already committed with a framework constant that is absent from API 36.
# Stage their validated repair so the gated bot commit cannot leave the branch uncompilable.
subprocess.run(["git", "add", *pcm_metadata_paths], cwd=ROOT, check=True)

print("Direct Player app integration applied")
