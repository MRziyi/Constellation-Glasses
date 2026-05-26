// Top-level build file. Per-module config in app/build.gradle.kts.
plugins {
    id("com.android.application")  version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // Kotlin 2.0+ requires the Compose Compiler Gradle plugin
    // (separate from the old kotlinCompilerExtensionVersion approach).
    // Version must track the Kotlin version exactly.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
