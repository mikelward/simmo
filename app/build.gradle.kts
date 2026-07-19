plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

// Firebase (Crashlytics + Analytics) activates per build: these plugins wire
// the config and mapping upload only when the untracked google-services.json
// is present, so fresh clones and CI build with Firebase dormant. See SETUP.md.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
}

fun gitOutput(vararg args: String, fallback: String): String =
    try {
        val output = providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
        output.ifEmpty { fallback }
    } catch (_: Exception) {
        fallback
    }

// Monotonic versionCode as long as main only moves forward; Play rejects an
// AAB whose versionCode is <= the highest already uploaded. CI checks out with
// fetch-depth: 0 so the count isn't truncated by a shallow clone.
val gitCommitCount: Int =
    gitOutput("rev-list", "--count", "HEAD", fallback = "1").toIntOrNull() ?: 1
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")
val baseVersionName = "0.1"

android {
    namespace = "app.simmo"
    // Latest platform the remote-session provisioning hook seeds; bump to the
    // Android 17 SDK once the hook and CI provide it (see TODO.md).
    compileSdk = 36

    defaultConfig {
        applicationId = "app.simmo"
        // CallRedirectionService exists since API 29, but mapping a
        // PhoneAccountHandle to its subscription without OEM-specific guesswork
        // (TelephonyManager.getSubscriptionId(handle)) needs API 30 — and the
        // primary target is current Pixels anyway (SPEC).
        minSdk = 30
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
    }

    signingConfigs {
        // CI materializes a stable debug keystore from a secret and points
        // DEBUG_KEYSTORE_FILE at it, so successive Firebase App Distribution
        // builds carry the same signature and tester devices install them as
        // updates. Local builds without the env var fall through to AGP's
        // auto-generated ~/.android/debug.keystore. See
        // docs/firebase-app-distribution.md.
        getByName("debug") {
            val keystorePath = providers.environmentVariable("DEBUG_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DEBUG_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull
            }
        }
        // CI materializes the Play upload keystore from a secret for the
        // internal-track upload (see docs/play-store-internal-track.md). Play
        // App Signing re-signs with its managed key before delivery. Local
        // builds without RELEASE_KEYSTORE_FILE produce an unsigned release
        // AAB, so forks and fresh clones build cleanly.
        create("release") {
            val keystorePath = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Only attach the release signingConfig when CI has populated it;
            // an unset storeFile would fail bundleRelease for anyone without
            // the secrets.
            if (!providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        // VERSION_NAME for the About dialog — a compile-time constant, so the
        // version never needs a PackageManager IPC on the composition path.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    // Compiled into every build so the telemetry wiring compiles, but inert
    // (never initialized) unless the build had a google-services.json.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libphonenumber)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}
