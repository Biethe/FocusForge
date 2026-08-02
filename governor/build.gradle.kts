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

/**
 * The cross-silicon exhibit: profile whatever machine this runs on.
 *
 * Deliberately a JavaExec on the plain JVM — no Android, no device, no model file — so the
 * arm64 CI runner executes exactly the code path the phone does.
 */
tasks.register<JavaExec>("deriveProfile") {
    group = "verification"
    description = "Run the self-benchmark on this machine and write a device profile"
    mainClass.set("dev.aarchmage.ProfileMain")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        "--out", project.findProperty("profileOut")?.toString()
            ?: "${rootProject.projectDir}/bench/profiles/host.device.profile.json",
        "--label", project.findProperty("profileLabel")?.toString() ?: "host",
        "--budget-ms", project.findProperty("profileBudgetMs")?.toString() ?: "60000",
    )
}

/** Re-derive the phone's profile from its committed, real-device measurements. */
tasks.register<JavaExec>("deriveA20eProfile") {
    group = "verification"
    description = "Derive the A20e profile from bench/results (no re-measurement)"
    mainClass.set("dev.aarchmage.ProfileMain")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        "--from-results", "${rootProject.projectDir}/bench/results/a20e-threads-kvcache-20260802.json",
        "--out", "${rootProject.projectDir}/bench/profiles/a20e.device.profile.json",
        "--label", "samsung-galaxy-a20e",
    )
}
