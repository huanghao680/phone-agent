plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.phoneagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phoneagent"
        minSdk = 26
        // targetSdk 28 is deliberate: Android 10+ blocks exec() of files in app-writable
        // storage when targetSdk >= 29 (W^X), and the embedded Node runtime must be
        // executable from filesDir. Same tradeoff Termux makes. Not distributed via Play.
        targetSdk = 28
        versionCode = 5
        versionName = "0.4.0"
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
    implementation("com.github.topjohnwu.libsu:core:5.2.2")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-core-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:$miuix")
}
