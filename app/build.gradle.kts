plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
}

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
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libphonenumber)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
