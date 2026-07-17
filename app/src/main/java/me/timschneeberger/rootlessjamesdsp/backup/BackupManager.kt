package me.timschneeberger.rootlessjamesdsp.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Job
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.preference.FileLibraryPreference
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.broadcastPresetLoadEvent
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.storage.Tar
import okio.buffer
import okio.gzip
import okio.sink
import okio.source
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class BackupManager(private val context: Context): KoinComponent {
    private val preferences: Preferences.App by inject()
    var job: Job? = null

    /**
     * Create backup file from database
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    fun createBackup(uri: Uri, isAutoBackup: Boolean): String {
        Timber.d("Creating backup (auto=$isAutoBackup) to $uri")

        var file: UniFile? = null
        try {
            file = (if (isAutoBackup) {
                        // Get dir of file and create
                        var dir = UniFile.fromUri(context, uri)
                        dir = dir.createDirectory("automatic")

                        // Delete older backups
                        val numberOfBackups = preferences.get<String>(R.string.key_backup_maximum).toIntOrNull() ?: 2
                        val backupRegex = Regex("""jamesdsp_backup_\d+-\d+-\d+_\d+-\d+.tar.gz""")
                        dir.listFiles { _, filename -> backupRegex.matches(filename) }
                            .orEmpty()
                            .sortedByDescending { it.name }
                            .drop(numberOfBackups - 1)
                            .forEach { it.delete() }

                        // Create new file to place backup
                        dir.createFile(getBackupFilename())
                    } else {
                        UniFile.fromUri(context, uri)
                    })
                ?: throw Exception("Couldn't create backup file")

            if (!file.isFile) {
                throw IllegalStateException("Failed to get handle on file")
            }

            Tar.Composer(file.openOutputStream().sink().gzip().buffer().outputStream()).use { c ->
                c.metadata = mutableMapOf(
                    META_MIN_VERSION_CODE to BuildConfig.VERSION_CODE.toString(),
                    META_FLAVOR to BuildConfig.FLAVOR,
                    META_IS_BACKUP to true.toString(),
                    META_HAS_DEVICE_PROFILES to false.toString()
                )

                File(context.applicationInfo.dataDir + "/shared_prefs")
                    .listFiles()
                    ?.filter { it.name.startsWith("dsp_") }
                    ?.filter { it.extension == "xml" }
                    ?.forEach { c.add(it, "shared_prefs/${it.name}") }

                if(preferences.get<Boolean>(R.string.key_device_profiles_enable)) {
                    c.metadata[META_HAS_DEVICE_PROFILES] = true.toString()
                    File(context.applicationInfo.dataDir + "/files/profiles").let { root ->
                        root
                            .walkTopDown()
                            .forEach { c.add(it, "profiles/${it.toRelativeString(root)}") }
                    }
                }

                FileLibraryPreference.types.entries.forEach { entry ->
                    File(context.getExternalFilesDir(null), "/${entry.key}")
                        .listFiles()
                        ?.filter { entry.value.any { ext -> it.absolutePath.endsWith(ext) } }
                        ?.forEach { c.add(it, "${entry.key}/${it.name}") }
                }
            }


            return file.uri.toString()
        } catch (e: Exception) {
            Timber.e(e)
            file?.delete()
            throw e
        }
    }

    fun restoreBackup(uri: Uri, dirty: Boolean) {
        Timber.d("Restoring backup from $uri")

        val targetFolder = File(context.cacheDir, "restore-${UUID.randomUUID()}")
        try {
            val metadata = context.contentResolver.openInputStream(uri)
                ?.source()
                ?.gzip()
                ?.buffer()
                ?.inputStream()
                ?.use { stream -> Tar.Reader(stream, ::isKnownFile).extract(targetFolder) }

            if(metadata == null) {
                throw UnsupportedOperationException(context.getString(R.string.backup_restore_error_format))
            }

            // Early RootlessJamesDSP backups used minVersionCode and did not include is_backup.
            // Preserve that compatibility, while rejecting archives explicitly marked as non-backups.
            if (isExplicitNonBackup(metadata)) {
                throw UnsupportedOperationException(context.getString(R.string.backup_restore_error_format))
            }

            if (!targetFolder.walkTopDown().any { it.isFile }) {
                throw UnsupportedOperationException(context.getString(R.string.backup_restore_error_format))
            }

            val minimumVersionCode = getMinimumVersionCode(metadata)
            if(BuildConfig.VERSION_CODE < minimumVersionCode) {
                throw UnsupportedOperationException(context.getString(R.string.backup_restore_error_version_too_new))
            }

            val enableDeviceProfiles = applyRestoredFiles(targetFolder, dirty)

            if(enableDeviceProfiles)
                preferences.set(R.string.key_device_profiles_enable, true)
            else if(!dirty)
                preferences.set(R.string.key_device_profiles_enable, false)

            context.broadcastPresetLoadEvent()
            context.sendLocalBroadcast(Intent(Constants.ACTION_BACKUP_RESTORED))
        } finally {
            targetFolder.deleteRecursively()
        }
    }

    /**
     * Applies a validated staging tree while preserving a rollback copy of the current DSP data.
     * A failed clean or merge restore therefore cannot be reported as successful with partially
     * deleted preferences, profiles, or library files.
     */
    private fun applyRestoredFiles(stagingRoot: File, dirty: Boolean): Boolean {
        val sharedPreferences = File(context.applicationInfo.dataDir, "shared_prefs")
        val deviceProfiles = File(context.applicationInfo.dataDir, "files/profiles")
        val externalFiles = context.getExternalFilesDir(null)
            ?: throw IOException("External app storage is unavailable")

        val rollbackId = UUID.randomUUID().toString()
        val internalRollback = File(context.cacheDir, "restore-rollback-$rollbackId")
        val externalRollback = File(externalFiles, ".restore-rollback-$rollbackId")
        val rollbackPreferences = File(internalRollback, "shared_prefs")
        val rollbackProfiles = File(internalRollback, "profiles")
        var liveStateModified = false

        try {
            snapshotDspPreferences(sharedPreferences, rollbackPreferences)
            copyDirectoryOrThrow(deviceProfiles, rollbackProfiles)
            FileLibraryPreference.types.keys.forEach { directoryName ->
                copyDirectoryOrThrow(
                    File(externalFiles, directoryName),
                    File(externalRollback, directoryName)
                )
            }

            liveStateModified = true
            if (!dirty) clearLiveDspData(sharedPreferences, deviceProfiles, externalFiles)
            copyStagedDspData(stagingRoot, sharedPreferences, deviceProfiles, externalFiles)

            return File(stagingRoot, "profiles").isDirectory
        } catch (restoreFailure: Exception) {
            if (liveStateModified) {
                try {
                    clearLiveDspData(sharedPreferences, deviceProfiles, externalFiles)
                    restoreDspPreferences(rollbackPreferences, sharedPreferences)
                    copyDirectoryOrThrow(rollbackProfiles, deviceProfiles)
                    FileLibraryPreference.types.keys.forEach { directoryName ->
                        copyDirectoryOrThrow(
                            File(externalRollback, directoryName),
                            File(externalFiles, directoryName)
                        )
                    }
                } catch (rollbackFailure: Exception) {
                    Timber.e(rollbackFailure, "Backup restore rollback failed")
                    restoreFailure.addSuppressed(rollbackFailure)
                }
            }
            throw restoreFailure
        } finally {
            internalRollback.deleteRecursively()
            externalRollback.deleteRecursively()
        }
    }

    private fun snapshotDspPreferences(source: File, target: File) {
        source.listFiles(::isDspPreferenceFile).orEmpty().forEach { preference ->
            target.mkdirs()
            preference.copyTo(File(target, preference.name), overwrite = true)
        }
    }

    private fun restoreDspPreferences(source: File, target: File) {
        source.listFiles(::isDspPreferenceFile).orEmpty().forEach { preference ->
            target.mkdirs()
            preference.copyTo(File(target, preference.name), overwrite = true)
        }
    }

    private fun copyStagedDspData(
        stagingRoot: File,
        sharedPreferences: File,
        deviceProfiles: File,
        externalFiles: File
    ) {
        restoreDspPreferences(File(stagingRoot, "shared_prefs"), sharedPreferences)
        copyDirectoryOrThrow(File(stagingRoot, "profiles"), deviceProfiles)
        FileLibraryPreference.types.keys.forEach { directoryName ->
            copyDirectoryOrThrow(
                File(stagingRoot, directoryName),
                File(externalFiles, directoryName)
            )
        }
    }

    private fun clearLiveDspData(
        sharedPreferences: File,
        deviceProfiles: File,
        externalFiles: File
    ) {
        sharedPreferences.listFiles(::isDspPreferenceFile).orEmpty().forEach { preference ->
            if (!preference.delete()) throw IOException("Unable to replace DSP preferences")
        }
        deleteDirectoryOrThrow(deviceProfiles)
        FileLibraryPreference.types.keys.forEach { directoryName ->
            deleteDirectoryOrThrow(File(externalFiles, directoryName))
        }
    }

    private fun copyDirectoryOrThrow(source: File, target: File) {
        if (!source.exists()) return
        if (!source.isDirectory || !source.copyRecursively(target, overwrite = true)) {
            throw IOException("Unable to copy restored DSP data")
        }
    }

    private fun deleteDirectoryOrThrow(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("Unable to replace restored DSP data")
        }
    }

    private fun isDspPreferenceFile(file: File): Boolean {
        return file.isFile && file.name.startsWith("dsp_") &&
            file.extension.equals("xml", ignoreCase = true)
    }

    companion object {
        private const val META_MIN_VERSION_CODE = "min_version_code"
        private const val META_MIN_VERSION_CODE_LEGACY = "minVersionCode"
        private const val META_FLAVOR = "flavor"
        private const val META_HAS_DEVICE_PROFILES = "has_device_profiles"
        const val META_IS_BACKUP = "is_backup"

        private val supportedMimeTypes = arrayOf(
            "application/gzip",
            "application/x-gzip",
            "application/tar+gzip",
            "application/x-compressed-tar",
            "application/x-gtar",
            "application/x-tar",
            "application/octet-stream",
            "application/*"
        )

        internal fun isKnownFile(name: String): Boolean {
            val path = name.split('/')
            if (path.any { it.isBlank() }) return false

            return when (path.firstOrNull()) {
                "shared_prefs" -> path.size == 2 &&
                    path.last().startsWith("dsp_") &&
                    path.last().endsWith(".xml", ignoreCase = true)
                "profiles" -> path.size in 2..3 &&
                    path.last() in setOf("profile.json", "profile.tar")
                else -> path.size == 2 && FileLibraryPreference.types[path.first()]?.any { extension ->
                    path.last().lowercase(Locale.ROOT).endsWith(extension)
                } == true
            }
        }

        fun getSupportedMimeTypes(): Array<String> {
            return supportedMimeTypes.clone()
        }

        internal fun getMinimumVersionCode(metadata: Map<String, String>): Int {
            return metadata[META_MIN_VERSION_CODE]?.toIntOrNull()
                ?: metadata[META_MIN_VERSION_CODE_LEGACY]?.toIntOrNull()
                ?: 0
        }

        internal fun isExplicitNonBackup(metadata: Map<String, String>): Boolean {
            return metadata[META_IS_BACKUP]?.equals(false.toString(), ignoreCase = true) == true
        }

        fun getBackupFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            return "jamesdsp_backup_$date.tar.gz"
        }
    }
}
