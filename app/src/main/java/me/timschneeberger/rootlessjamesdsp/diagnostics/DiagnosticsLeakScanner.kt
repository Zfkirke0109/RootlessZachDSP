package me.timschneeberger.rootlessjamesdsp.diagnostics

/** Conservative final gate applied before a diagnostics bundle may be shared. */
object DiagnosticsLeakScanner {
    enum class Category {
        CONTENT_OR_FILE_URI,
        ANDROID_PRIVATE_OR_SHARED_PATH,
        WINDOWS_USER_PATH,
        SECRET_ASSIGNMENT,
        DEVICE_SERIAL,
        ADB_OR_NETWORK_ENDPOINT,
    }

    data class Finding(
        val category: Category,
        val lineNumber: Int,
    )

    private data class Rule(val category: Category, val pattern: Regex)

    private val rules = listOf(
        Rule(
            Category.CONTENT_OR_FILE_URI,
            Regex("(?i)\\b(?:content|file)://"),
        ),
        Rule(
            Category.ANDROID_PRIVATE_OR_SHARED_PATH,
            Regex("(?i)(?:/storage/(?:emulated/\\d+|self/primary)|/sdcard|/data/user/\\d+|/data/data)/"),
        ),
        Rule(
            Category.WINDOWS_USER_PATH,
            Regex("(?i)\\b[A-Z]:\\\\Users\\\\"),
        ),
        Rule(
            Category.SECRET_ASSIGNMENT,
            Regex(
                "(?i)\\b(?:token|password|passwd|secret|keystorePassword|keyPassword|apiKey)" +
                    "\\s*[:=]\\s*(?!<redacted>|null|false|none)\\S+",
            ),
        ),
        Rule(
            Category.DEVICE_SERIAL,
            Regex(
                "(?i)\\b(?:deviceSerial|adbSerial|serialNumber)\\s*[:=]\\s*" +
                    "(?!<redacted>|null|none)\\S+",
            ),
        ),
        Rule(
            Category.ADB_OR_NETWORK_ENDPOINT,
            Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}:\\d{2,5}\\b"),
        ),
    )

    fun scan(text: String): List<Finding> {
        val findings = mutableListOf<Finding>()
        text.lineSequence().forEachIndexed { index, line ->
            rules.forEach { rule ->
                if (rule.pattern.containsMatchIn(line)) {
                    findings += Finding(rule.category, index + 1)
                }
            }
        }
        return findings.distinct()
    }

    fun isSafeToShare(text: String): Boolean = scan(text).isEmpty()
}
