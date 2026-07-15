package me.timschneeberger.rootlessjamesdsp.utils.extensions

internal object AppIdentityValidator {
    fun check(
        actualPackageName: String,
        actualAppName: String,
        expectedPackageName: String,
        expectedAppName: String,
        isPlugin: Boolean,
        isDebug: Boolean,
    ): Int {
        if (isPlugin) return 0
        if (actualPackageName != expectedPackageName) return 1
        if (actualAppName != expectedAppName) return 2
        if (!isDebug && actualPackageName.endsWith(".debug")) return 3
        return 0
    }
}
