plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// HARD RULE, same as :core (CLAUDE.md §3): pure Kotlin/JVM, zero Android imports.
//
// This is not tidiness. The architect's Phase 6 item 4 requires the *same self-benchmark
// path* to run on the ubuntu-24.04-arm CI runner, and §4.4 forbids installing the Android
// SDK there. A single Android import in this module would make the cross-silicon exhibit
// impossible to build.
//
// Everything platform-specific — battery, thermal APIs, the share sheet, the progress UI —
// reaches this module through interfaces that :app implements.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
