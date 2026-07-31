import java.net.URI

plugins {
    // Versions come from settings.gradle.kts (pluginManagement.plugins).
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
        versionCode = 4
        versionName = "0.3.1-eyediag"
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

    buildFeatures {
        // Recordings carry the app version that produced them.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Every dependency has a docs/DECISIONS.md entry with its license (MIT/Apache-2.0/BSD only).
dependencies {
    // All signal logic lives in the pure-Kotlin :core module (CLAUDE.md §3).
    implementation(project(":core"))
    // CameraX (Apache-2.0)
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // ComponentActivity = LifecycleOwner for CameraX (Apache-2.0)
    implementation("androidx.activity:activity:1.9.0")
    // MediaPipe Tasks FaceLandmarker (Apache-2.0)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// The FaceLandmarker model is fetched at build time and bundled as an asset.
// It is NEVER committed (models/ + *.task are gitignored); pinned version below.
val faceLandmarkerUrl =
    "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task"

tasks.register("downloadModels") {
    val outFile = layout.projectDirectory
        .file("src/main/assets/models/face_landmarker.task").asFile
    outputs.file(outFile)
    doLast {
        if (!outFile.exists() || outFile.length() < 1_000_000) {
            outFile.parentFile.mkdirs()
            URI(faceLandmarkerUrl).toURL().openStream().use { input: java.io.InputStream ->
                outFile.outputStream().use { input.copyTo(it) }
            }
            println("Downloaded face_landmarker.task: ${outFile.length()} bytes")
        }
    }
}
tasks.named("preBuild") { dependsOn("downloadModels") }
