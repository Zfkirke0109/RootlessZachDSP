package me.timschneeberger.rootlessjamesdsp.session.dump.utils

import android.content.Context
import timber.log.Timber

/**
 * Used to retrieve complementary data for AudioServiceDumpProvider.
 *
 * AudioFlinger legitimately reports more than one session for a process. The parsed output keeps
 * every unique (sid, pid, uid) tuple so callers can treat PID-to-session lookup as a multimap.
 */
object AudioFlingerServiceDumpUtils {
    data class Dataset(val sid: Int, val pid: Int, val uid: Int?) {
        override fun toString(): String = "sid=$sid; pid=$pid; uid=$uid"
    }

    fun dump(context: Context): List<Dataset>? {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        dump ?: return null

        return process(dump)
    }

    internal fun process(dump: String): List<Dataset>? {
        // API 29
        val tableHeadRegex = Regex("""session\s+pid\s+count""", RegexOption.IGNORE_CASE)
        val tableBodyRegex = Regex("""(\d+)\s+(\d+)\s+(\d+)""")
        // API 30+ (also observed as table version 30 on Android 16 / One UI 8.5)
        val tableHeadRegex30 = Regex("""session\s+cnt\s+pid\s+uid\s+name""", RegexOption.IGNORE_CASE)
        val tableBodyRegex30 = Regex("""(\d+)\s+(\d+)\s+(\d+)\s+(\d+)""")

        val dataset = linkedSetOf<Dataset>()
        var tableHeadApiVersion = 29
        var headerLine = Int.MIN_VALUE
        var lastLineIsTable = false

        dump.lines().forEachIndexed { index, line ->
            if (line.contains("Global session refs", ignoreCase = true)) {
                headerLine = index
            }

            // Look for table column titles after the header.
            if (headerLine + 1 == index) {
                // Auto-detect rather than relying on the platform API level.
                tableHeadApiVersion = when {
                    tableHeadRegex30.containsMatchIn(line) -> 30
                    tableHeadRegex.containsMatchIn(line) -> 29
                    else -> {
                        Timber.e("Failed to determine table version. Table head: $line")
                        return null
                    }
                }
                Timber.d("Table version $tableHeadApiVersion")
                lastLineIsTable = true
            } else if (lastLineIsTable) {
                try {
                    when (tableHeadApiVersion) {
                        29 -> {
                            val match = tableBodyRegex.find(line)
                            if (match != null) {
                                val sid = match.groups[1]?.value?.toInt()
                                val pid = match.groups[2]?.value?.toInt()
                                if (sid != null && pid != null) {
                                    dataset.add(Dataset(sid, pid, null))
                                } else {
                                    throw IndexOutOfBoundsException("sid=$sid; pid=$pid")
                                }
                            } else {
                                finishTable(line)
                                lastLineIsTable = false
                            }
                        }

                        30 -> {
                            val match = tableBodyRegex30.find(line)
                            if (match != null) {
                                val sid = match.groups[1]?.value?.toIntOrNull()
                                val pid = match.groups[3]?.value?.toIntOrNull()
                                val uid = match.groups[4]?.value?.toIntOrNull()
                                if (sid != null && pid != null) {
                                    dataset.add(Dataset(sid, pid, uid))
                                } else {
                                    throw IndexOutOfBoundsException("sid=$sid; uid=$uid; pid=$pid")
                                }
                            } else {
                                finishTable(line)
                                lastLineIsTable = false
                            }
                        }
                    }
                } catch (ex: IndexOutOfBoundsException) {
                    Timber.e("Incomplete table body pattern match. Line: $line")
                    Timber.e(ex)
                }
            }
        }

        Timber.d("Dump processed")
        return dataset.toList()
    }

    private fun finishTable(line: String) {
        when {
            line.isBlank() -> Timber.d("Reached end of AudioFlinger session table")
            isRecognizedNonRowMetadata(line) -> Timber.d(
                "Reached recognized AudioFlinger metadata after session table: %s",
                line.trim().substringBefore('='),
            )
            else -> Timber.i("Unmatched table body pattern. Line: $line")
        }
    }

    internal fun isRecognizedNonRowMetadata(line: String): Boolean {
        return AUDIO_FLINGER_PROPERTY_LINE.matches(line.trim())
    }

    fun dumpString(context: Context): String {
        val dump = DumpUtils.dumpAll(context, TARGET_SERVICE)
        val sb = StringBuilder("=====> $TARGET_SERVICE raw dump\n")
        sb.append(dump)
        sb.append("\n\n")
        sb.append("=====> $TARGET_SERVICE processed dump\n")
        process(dump ?: "")?.forEach {
            sb.append("$it\n")
        }
        return sb.toString()
    }

    private val AUDIO_FLINGER_PROPERTY_LINE = Regex("""^m[A-Za-z0-9_]+\s*=.*$""")

    const val TARGET_SERVICE = "media.audio_flinger"
}
