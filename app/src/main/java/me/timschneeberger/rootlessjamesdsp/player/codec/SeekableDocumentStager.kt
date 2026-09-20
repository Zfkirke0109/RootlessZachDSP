package me.timschneeberger.rootlessjamesdsp.player.codec

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min

/** Bounded fallback for document providers that expose codec inputs as non-seekable pipes. */
object SeekableDocumentStager {
    private const val FILE_PREFIX = "rootlesszach-codec-"
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val RESERVED_CACHE_BYTES = 128L * 1024 * 1024
    private const val MAX_STAGED_FILE_BYTES = 8L * 1024 * 1024 * 1024
    private const val STALE_FILE_AGE_MILLIS = 24L * 60 * 60 * 1_000

    @Synchronized
    fun stage(context: Context, uri: Uri, suffix: String): File {
        val cacheDir = context.applicationContext.cacheDir
        removeStaleFiles(cacheDir)
        val budget = min(MAX_STAGED_FILE_BYTES, cacheDir.usableSpace - RESERVED_CACHE_BYTES)
        if (budget <= 0) {
            throw IOException("Not enough cache space to stage a seekable audio document")
        }

        val staged = File.createTempFile(FILE_PREFIX, suffix, cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(staged).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        if (total > budget) {
                            throw IOException(
                                "Audio document exceeds the safe seekable-cache budget ($budget bytes)",
                            )
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("The document provider could not open the audio stream")
            return staged
        } catch (error: Throwable) {
            staged.delete()
            throw error
        }
    }

    @Synchronized
    fun removeStaleFiles(cacheDir: File, nowMillis: Long = System.currentTimeMillis()) {
        cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith(FILE_PREFIX)
        }.orEmpty().forEach { file ->
            if (nowMillis - file.lastModified() >= STALE_FILE_AGE_MILLIS) {
                file.delete()
            }
        }
    }
}
