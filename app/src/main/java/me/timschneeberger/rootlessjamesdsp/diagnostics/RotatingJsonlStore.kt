
package me.timschneeberger.rootlessjamesdsp.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * Small app-private JSONL store with one rotated generation.
 *
 * Callers must keep this object off the real-time audio thread. Each append is synchronized so a
 * future diagnostics UI/exporter can safely read or clear the store while the writer is active.
 */
class RotatingJsonlStore(
    directory: File,
    activeFileName: String = DEFAULT_ACTIVE_FILE_NAME,
    private val maximumActiveBytes: Long = DEFAULT_MAXIMUM_ACTIVE_BYTES,
    private val maximumLineBytes: Int = DEFAULT_MAXIMUM_LINE_BYTES,
) {
    private val directory = directory.absoluteFile
    private val activeFile = File(this.directory, activeFileName)
    private val rotatedFile = File(this.directory, "$activeFileName.1")

    init {
        require(maximumActiveBytes > 0L) { "maximumActiveBytes must be positive" }
        require(maximumLineBytes > 0) { "maximumLineBytes must be positive" }
        require(activeFileName.isNotBlank()) { "activeFileName must not be blank" }
        require(!activeFileName.contains('/') && !activeFileName.contains('\\')) {
            "activeFileName must be a simple file name"
        }
    }

    @Synchronized
    fun appendLine(line: String) {
        appendLines(listOf(line))
    }

    @Synchronized
    fun appendLines(lines: Collection<String>) {
        if (lines.isEmpty()) return
        ensureDirectory()

        val encodedLines = lines.map { line ->
            val bytes = "${normalizeLine(line)}\n".toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= maximumLineBytes) {
                "Diagnostic line exceeds maximumLineBytes ($maximumLineBytes)"
            }
            require(bytes.size.toLong() <= maximumActiveBytes) {
                "Diagnostic line exceeds maximumActiveBytes ($maximumActiveBytes)"
            }
            bytes
        }

        var projectedBytes = if (activeFile.exists()) activeFile.length() else 0L
        val pending = ArrayList<ByteArray>(encodedLines.size)

        encodedLines.forEach { bytes ->
            if (projectedBytes > 0L && projectedBytes + bytes.size > maximumActiveBytes) {
                appendBatch(pending)
                pending.clear()
                rotate()
                projectedBytes = 0L
            }
            pending += bytes
            projectedBytes += bytes.size
        }

        appendBatch(pending)
    }

    @Synchronized
    fun readRecentLines(maximumLines: Int = 200): List<String> {
        require(maximumLines >= 0) { "maximumLines must not be negative" }
        if (maximumLines == 0 || !activeFile.exists()) return emptyList()

        val ring = ArrayDeque<String>(maximumLines)
        activeFile.useLines(StandardCharsets.UTF_8) { sequence ->
            sequence.forEach { line ->
                if (ring.size == maximumLines) ring.removeFirst()
                ring.addLast(line)
            }
        }
        return ring.toList()
    }

    @Synchronized
    fun clear() {
        if (activeFile.exists() && !activeFile.delete()) {
            activeFile.writeText("")
        }
        if (rotatedFile.exists() && !rotatedFile.delete()) {
            rotatedFile.writeText("")
        }
    }

    fun activeFile(): File = activeFile

    fun rotatedFile(): File = rotatedFile

    private fun appendBatch(lines: Collection<ByteArray>) {
        if (lines.isEmpty()) return
        FileOutputStream(activeFile, true)
            .buffered(DEFAULT_STREAM_BUFFER_BYTES)
            .use { output ->
                lines.forEach(output::write)
            }
    }

    private fun ensureDirectory() {
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            error("Unable to create diagnostics directory")
        }
    }

    private fun rotate() {
        if (rotatedFile.exists() && !rotatedFile.delete()) {
            rotatedFile.writeText("")
        }
        if (!activeFile.renameTo(rotatedFile)) {
            activeFile.copyTo(rotatedFile, overwrite = true)
            if (!activeFile.delete()) activeFile.writeText("")
        }
    }

    private fun normalizeLine(line: String): String =
        line.replace('\r', ' ').replace('\n', ' ')

    companion object {
        const val DEFAULT_ACTIVE_FILE_NAME = "rootless_audio_events.jsonl"
        const val DEFAULT_MAXIMUM_ACTIVE_BYTES = 5L * 1024L * 1024L
        const val DEFAULT_MAXIMUM_LINE_BYTES = 256 * 1024
        private const val DEFAULT_STREAM_BUFFER_BYTES = 64 * 1024
    }
}
