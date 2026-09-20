package me.timschneeberger.rootlessjamesdsp.utils.storage

import android.content.Context
import org.kamranzafar.jtar.TarEntry
import org.kamranzafar.jtar.TarInputStream
import org.kamranzafar.jtar.TarOutputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Tar {
    private const val FILE_METADATA = "metadata"
    private const val MAX_METADATA_BYTES = 64L * 1024L
    private const val MAX_ENTRY_PATH_LENGTH = 1024
    private const val MAX_ENTRY_PATH_DEPTH = 16

    data class Limits(
        val maxEntryCount: Int = 4096,
        val maxEntryBytes: Long = 256L * 1024L * 1024L,
        val maxTotalBytes: Long = 512L * 1024L * 1024L
    ) {
        init {
            require(maxEntryCount > 0)
            require(maxEntryBytes > 0)
            require(maxTotalBytes > 0)
        }
    }

    /**
     * Create tar composer
     * @throws FileNotFoundException if file already exists as a directory or cannot be created for other reasons
     * @throws SecurityException if write access is denied
     */
    class Composer: AutoCloseable, KoinComponent {
        constructor(outputStream: OutputStream) {
            stream = TarOutputStream(outputStream)
        }

        constructor(file: File) {
            stream = TarOutputStream(file)
        }

        private val context: Context by inject()
        private val stream: TarOutputStream

        var metadata = mutableMapOf<String, String>()

        fun add(file: File, entryPath: String? = null): Boolean {
            if (!file.exists() || file.isDirectory) {
                Timber.e("addFile: ${file.absolutePath} is not valid")
                return false
            }

            stream.putNextEntry(TarEntry(file, (entryPath ?: file.name)))
            BufferedInputStream(FileInputStream(file)).use { origin ->
                var count: Int
                val data = ByteArray(2048)
                while (origin.read(data).also { count = it } != -1) {
                    stream.write(data, 0, count)
                }
                stream.flush()
            }
            return true
        }

        override fun close() {
            add(
                File(context.cacheDir, FILE_METADATA).apply {
                    writeText(
                        metadata
                            .map { "${it.key}=${it.value}" }
                            .joinToString("\n")
                    )
                }
            )
            stream.close()
        }
    }

    /** Create tar reader */
    class Reader(
        private val inStream: InputStream,
        private val shouldExtract: ((entryName: String) -> Boolean) = { true },
        private val limits: Limits = Limits()
    ) {
        private fun process(
            onNextEntry: (
                tis: TarInputStream,
                entryName: String,
                shouldKeep: Boolean
            ) -> Unit
        ) {
            TarInputStream(BufferedInputStream(inStream)).use { tis ->
                var entry: TarEntry?
                var entryCount = 0
                val seenEntries = mutableSetOf<String>()
                while (tis.nextEntry.also { entry = it } != null) {
                    val rawEntryName = entry?.name ?: break
                    val isDirectory = rawEntryName.endsWith('/')
                    val entryName = normalizeArchiveEntryName(
                        if (isDirectory) rawEntryName.dropLast(1) else rawEntryName
                    ) ?: throw IOException("Unsafe archive entry path")

                    entryCount++
                    if (entryCount > limits.maxEntryCount) {
                        throw IOException("Archive contains too many entries")
                    }

                    val uniqueName = if (isDirectory) "$entryName/" else entryName
                    if (!seenEntries.add(uniqueName)) {
                        throw IOException("Archive contains duplicate entries")
                    }

                    val shouldKeep = !isDirectory &&
                        (shouldExtract(entryName) || entryName == FILE_METADATA)
                    if (!shouldKeep) {
                        Timber.w("Entry name ignored: $entryName")
                    }

                    onNextEntry(tis, entryName, shouldKeep)
                }
            }
        }

        private fun transferEntry(
            stream: TarInputStream,
            destination: OutputStream?,
            totalBytes: LongArray,
            maxEntryBytes: Long = limits.maxEntryBytes
        ) {
            var entryBytes = 0L
            val data = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(data)
                if (count == -1) break

                if (entryBytes > maxEntryBytes - count) {
                    throw IOException("Archive entry exceeds the expanded size limit")
                }
                if (totalBytes[0] > limits.maxTotalBytes - count) {
                    throw IOException("Archive exceeds the total expanded size limit")
                }

                entryBytes += count
                totalBytes[0] += count
                destination?.write(data, 0, count)
            }
        }

        fun validate(): Boolean {
            Timber.d("Validating preset")

            var payloadCount = 0
            val totalBytes = longArrayOf(0L)
            try {
                process { stream, name, shouldKeep ->
                    if (shouldKeep && name != FILE_METADATA) payloadCount++
                    transferEntry(stream, null, totalBytes)
                }
            }
            catch(ex: Exception) {
                Timber.e("Validation failed due to exception")
                Timber.w(ex)
                return false
            }

            if (payloadCount < 1) {
                Timber.e("Archive did not contain any useful data")
                return false
            }

            return true
        }

        fun extract(targetFolder: File) : Map<String, String>? {
            if(targetFolder.exists() && !targetFolder.deleteRecursively()) {
                Timber.e("Unable to clear archive staging directory")
                return null
            }
            if(!targetFolder.mkdirs()) {
                Timber.e("Unable to create archive staging directory")
                return null
            }

            val metadataBytes = ByteArrayOutputStream()
            val totalBytes = longArrayOf(0L)
            try {
                process { stream, name, shouldKeep ->
                    when {
                        !shouldKeep -> transferEntry(stream, null, totalBytes)
                        name == FILE_METADATA -> transferEntry(
                            stream,
                            metadataBytes,
                            totalBytes,
                            MAX_METADATA_BYTES
                        )
                        else -> {
                            val destination = resolveContainedFile(targetFolder, name)
                            if (destination.parentFile?.mkdirs() == false &&
                                destination.parentFile?.isDirectory != true) {
                                throw IOException("Unable to create archive entry directory")
                            }
                            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                                transferEntry(stream, output, totalBytes)
                                output.flush()
                            }
                        }
                    }
                }
                metadataBytes.flush()
            }
            catch(ex: Exception) {
                Timber.e("Extraction failed")
                Timber.w(ex)
                targetFolder.deleteRecursively()
                return null
            }

            return mutableMapOf<String, String>().apply {
                metadataBytes.toString(Charsets.UTF_8.name()).lines().forEach {
                    val args = it.split("=", limit = 2)
                    if(args.size < 2)
                        return@forEach

                    this[args[0]] = args[1].trim()
                }
            }
        }

        private fun resolveContainedFile(targetFolder: File, entryName: String): File {
            val root = targetFolder.canonicalFile
            val destination = File(root, entryName).canonicalFile
            val rootPrefix = root.path + File.separator
            if (destination == root || !destination.path.startsWith(rootPrefix)) {
                throw IOException("Archive entry escaped the staging directory")
            }
            return destination
        }
    }

    internal fun normalizeArchiveEntryName(entryName: String): String? {
        if (entryName.isBlank() || entryName.length > MAX_ENTRY_PATH_LENGTH) return null
        if (entryName.startsWith('/') || entryName.contains('\\') || entryName.contains('\u0000')) {
            return null
        }
        if (entryName.matches(Regex("^[A-Za-z]:($|/).*"))) return null

        val segments = entryName.split('/')
        if (segments.size > MAX_ENTRY_PATH_DEPTH ||
            segments.any { it.isBlank() || it == "." || it == ".." }) {
            return null
        }

        return segments.joinToString("/")
    }

}
