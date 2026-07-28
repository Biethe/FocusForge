plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.focusforge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.focusforge"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"
        ndk {
            // A20e is arm64-v8a only per CLAUDE.md; we never ship other ABIs.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") {
            // Committed debug-only keystore (standard Android debug password) so every
            // CI build has the same signature and the phone can update in place
            // instead of requiring uninstall. Never used for anything but dev builds.
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No runtime dependencies yet — Kotlin stdlib only. Every future dependency
// needs a docs/DECISIONS.md entry with its license (MIT/Apache-2.0/BSD only).
dependencies {
}
