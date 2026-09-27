plugins {
    id("com.android.library")
}

// Vendored from termux-app v0.118.1 (Apache-2.0). See NOTICE-termux-app.md.
android {
    namespace = "com.termux.view"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.8.0")
    api(project(":terminal-emulator"))
}
