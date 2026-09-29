plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.phoneagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        // targetSdk 28 is deliberate: Android 10+ blocks exec() of files in app-writable
        // storage when targetSdk >= 29 (W^X), and the embedded Node runtime must be
        // executable from filesDir. Same tradeoff Termux makes. Not distributed via Play.
        targetSdk = 28
        versionCode = 27
        versionName = "0.7.10"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") {
            // Committed keystore (throwaway debug credentials) so every build has
            // the same signature and `adb install -r` updates work across CI runs.
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(project(":terminal-view"))
    implementation(project(":terminal-emulator"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.9")
    // dsh session logs are multi-frame zstd JSONL; the aar carries the arm64 native
    implementation("com.github.luben:zstd-jni:1.5.7-3@aar")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // last miuix line compatible with androidx Compose 1.7 / compileSdk 34
    implementation("top.yukonga.miuix.kmp:miuix-android:0.3.8")
}
