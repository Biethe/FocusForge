plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Pure Kotlin/JVM. HARD RULE (CLAUDE.md §3): zero Android imports in this module —
// every signal must be computable and testable on a plain JVM, which is what lets the
// replay tests run on the ubuntu-24.04-arm runner without an Android SDK.

kotlin {
    jvmToolchain(17)
}

dependencies {
    // kotlinx-serialization-json (Apache-2.0) — the recording format is defined once here
    // and read back byte-identically by the replay tests. See docs/DECISIONS.md.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    // Replay tests read the committed recordings; give them an absolute path so the
    // tests do not depend on the working directory.
    systemProperty("focusforge.replayDir", rootProject.file("bench/replays").absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
