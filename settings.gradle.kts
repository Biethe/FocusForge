pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Plugin versions live here (not in the root build script) so that a build which
    // does not include :app never resolves the Android Gradle Plugin at all.
    plugins {
        id("com.android.application") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "1.9.24"
        id("org.jetbrains.kotlin.jvm") version "1.9.24"
        id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "focusforge"

// :core is pure Kotlin/JVM — zero Android imports — so it runs on any JVM.
include(":core")

// CI split rule (CLAUDE.md §4.4): the ubuntu-24.04-arm runner runs :core JVM tests only
// and must NEVER have the Android SDK installed. `./gradlew -PcoreOnly :core:test` drops
// :app from the build, so nothing asks for the SDK or the Android Gradle Plugin.
if (!startParameter.projectProperties.containsKey("coreOnly")) {
    include(":app")
}
