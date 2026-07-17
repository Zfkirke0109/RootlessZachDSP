// Top-level build configuration for RootlessZachDSP.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            val testStorePath = providers.environmentVariable("ROOTLESS_TEST_KEYSTORE_PATH").orNull
            val testStorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            val testKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
            val testKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            val hasPersistentTestSigning = listOf(
                testStorePath,
                testStorePassword,
                testKeyAlias,
                testKeyPassword,
            ).all { !it.isNullOrBlank() }

            if (hasPersistentTestSigning) {
                val zachTest = signingConfigs.maybeCreate("zachTest").apply {
                    storeFile = rootProject.file(requireNotNull(testStorePath))
                    storePassword = testStorePassword
                    keyAlias = testKeyAlias
                    keyPassword = testKeyPassword
                    enableV1Signing = true
                    enableV2Signing = true
                    enableV3Signing = true
                    enableV4Signing = true
                }
                buildTypes.getByName("debug").signingConfig = zachTest
            }
        }

        // Apply the CI version override only after the module script has assigned its default 100.
        afterEvaluate {
            extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
                providers.environmentVariable("VERSION_CODE").orNull
                    ?.toIntOrNull()
                    ?.takeIf { it > 100 }
                    ?.let { defaultConfig.versionCode = it }
            }
        }

        dependencies.add("implementation", "androidx.core:core-splashscreen:1.0.1")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
