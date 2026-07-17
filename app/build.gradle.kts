plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.devtools.ksp") version AndroidConfig.kspVersion
    id("dev.rikka.tools.refine") version AndroidConfig.rikkaRefineVersion
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

android {
    val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    val rootlessApplicationId = "com.zfkirke0109.rootlesszachdsp"
    val rootlessAppLabel = "RootlessZachDSP"
    val rootApplicationId = "com.zfkirke0109.zachdsp.root"
    val rootAppLabel = "ZachDSP (Root)"
    val pluginApplicationId = "com.zfkirke0109.zachdsp.plugin"
    val pluginAppLabel = "ZachDSP Plugin"
    val releaseStorePath = providers.environmentVariable("GH_RELEASE_KEYSTORE_PATH").orNull
    val releaseStorePassword = providers.environmentVariable("GH_RELEASE_STORE_PASSWORD").orNull
    val releaseKeyAlias = providers.environmentVariable("GH_RELEASE_KEY_ALIAS").orNull
    val releaseKeyPassword = providers.environmentVariable("GH_RELEASE_KEY_PASSWORD").orNull
    val hasReleaseSigning = listOf(
        releaseStorePath,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

    compileSdk = AndroidConfig.compileSdk
    ndkVersion = "28.2.13676358"
    project.setProperty("archivesBaseName", "RootlessZachDSP-v${AndroidConfig.versionName}")

    defaultConfig {
        targetSdk = AndroidConfig.targetSdk
        versionCode = AndroidConfig.versionCode
        versionName = AndroidConfig.versionName
        manifestPlaceholders["label"] = rootlessAppLabel
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
        buildConfigField("boolean", "PREVIEW", "false")
        buildConfigField("boolean", "PLUGIN", "false")

        externalNativeBuild {
            cmake {
                arguments.addAll(
                    listOf(
                        "-DANDROID_ARM_NEON=ON",
                        "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    ),
                )
                cFlags.add(
                    "-std=gnu11 -Wno-incompatible-pointer-types " +
                        "-Wno-implicit-int -Wno-implicit-function-declaration",
                )
            }
        }

        ndk {
            abiFilters += supportedAbis
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("zachRelease") {
                storeFile = file(requireNotNull(releaseStorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-${getCommitCount()}"
            manifestPlaceholders["crashlyticsCollectionEnabled"] = "false"
        }
        getByName("release") {
            manifestPlaceholders += mapOf("crashlyticsCollectionEnabled" to "false")
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("zachRelease")
        }
        create("preview") {
            initWith(getByName("release"))
            buildConfigField("boolean", "PREVIEW", "true")
            versionNameSuffix = getByName("debug").versionNameSuffix
            matchingFallbacks.add("release")
        }
    }

    flavorDimensions += "version"
    flavorDimensions += "dependencies"
    productFlavors {
        create("fdroid") {
            dimension = "dependencies"
            buildConfigField("boolean", "FOSS_ONLY", "true")
            android.defaultConfig.externalNativeBuild.cmake.arguments += "-DNO_CRASHLYTICS=1"
        }
        create("full") {
            dimension = "dependencies"
            buildConfigField("boolean", "FOSS_ONLY", "false")
        }

        create("rootless") {
            dimension = "version"
            manifestPlaceholders["label"] = rootlessAppLabel
            applicationId = rootlessApplicationId
            AndroidConfig.minSdk = 29
            minSdk = AndroidConfig.minSdk
            buildConfigField("String", "EXPECTED_APP_NAME", "\"$rootlessAppLabel\"")
            buildConfigField("boolean", "ROOTLESS", "true")
            buildConfigField("boolean", "PLUGIN", "false")
        }
        create("root") {
            dimension = "version"
            manifestPlaceholders["label"] = rootAppLabel
            applicationId = rootApplicationId
            AndroidConfig.minSdk = 26
            minSdk = AndroidConfig.minSdk
            buildConfigField("String", "EXPECTED_APP_NAME", "\"$rootAppLabel\"")
            buildConfigField("boolean", "ROOTLESS", "false")
            buildConfigField("boolean", "PLUGIN", "false")
        }
        create("plugin") {
            dimension = "version"
            applicationId = pluginApplicationId
            manifestPlaceholders["label"] = pluginAppLabel
            AndroidConfig.minSdk = 26
            minSdk = AndroidConfig.minSdk
            buildConfigField("String", "EXPECTED_APP_NAME", "\"$pluginAppLabel\"")
            buildConfigField("boolean", "ROOTLESS", "false")
            buildConfigField("boolean", "PLUGIN", "true")
        }
    }

    sourceSets {
        getByName("debug").res.srcDirs("src/debug/res")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*supportedAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += "ObsoleteSdkInt"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = false
        renderScript = false
        shaders = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The installed application ID is fully rebranded above. The source namespace stays
    // at the upstream value in the foundation PR to keep the first transport changes
    // reviewable; a mechanical namespace migration is tracked separately.
    namespace = "me.timschneeberger.rootlessjamesdsp"
}

// RootlessZachDSP distributes the privacy-first FOSS dependency flavor. The inherited
// upstream `full` source set remains in history for comparison but is intentionally not
// built until it is decoupled from the upstream Firebase project.
androidComponents {
    beforeVariants(selector().withFlavor("dependencies" to "full")) { variant ->
        variant.enable = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.databinding:databinding-runtime:8.7.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    implementation("com.google.android.material:material:1.9.0")
    implementation("io.insert-koin:koin-android:3.3.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")

    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.github.bastienpaulfr:Treessence:1.0.0")
    implementation("org.kamranzafar:jtar:2.3")
    implementation("com.squareup.okio:okio:3.6.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    implementation(project(":codeview"))
    implementation("dev.rikka.shizuku:api:${AndroidConfig.shizukuVersion}")
    implementation("dev.rikka.shizuku:provider:${AndroidConfig.shizukuVersion}")
    implementation("com.github.tachiyomiorg:unifile:17bec43")
    "rootImplementation"("com.github.topjohnwu.libsu:core:5.0.4")

    implementation("dev.rikka.tools.refine:runtime:${AndroidConfig.rikkaRefineVersion}")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    compileOnly(project(":hidden-api-refined"))
    implementation(project(":hidden-api-impl"))

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.10")

    // Pluto 2.0.9's runtime lifecycle callbacks retain destroyed activities/fragments on
    // Android 16. Keep the API-compatible no-op artifacts in every build type while the app's
    // own Diagnostics, LeakCanary, StrictMode, Timber, and private JSONL reports remain active.
    debugImplementation("com.plutolib:pluto-no-op:2.0.9")
    "previewImplementation"("com.plutolib:pluto-no-op:2.0.9")
    releaseImplementation("com.plutolib:pluto-no-op:2.0.9")
    debugImplementation("com.plutolib.plugins:bundle-core-no-op:2.0.9")
    "previewImplementation"("com.plutolib.plugins:bundle-core-no-op:2.0.9")
    releaseImplementation("com.plutolib.plugins:bundle-core-no-op:2.0.9")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
